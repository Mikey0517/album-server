package com.example.album.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 极简 Flyway 式迁移器：
 * 1. 建 _migrations 表；
 * 2. 扫描 classpath:db/migration/V*.sql，按文件名字典序执行；
 * 3. 每个文件 + 记录插入在同一个事务中；
 * 4. 已执行的文件不再执行，已发布的迁移文件禁止修改。
 */
@Component
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public MigrationRunner(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS _migrations (
                  name       TEXT PRIMARY KEY,
                  applied_at TEXT NOT NULL
                )
                """);

        Set<String> applied = new HashSet<>(jdbc.queryForList(
                "SELECT name FROM _migrations", String.class));

        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        List<Resource> ordered = new ArrayList<>(List.of(resources));
        ordered.sort(Comparator.comparing(r -> filename(r)));

        for (Resource resource : ordered) {
            String name = filename(resource);
            if (applied.contains(name)) {
                continue;
            }
            apply(resource, name);
            applied.add(name);
        }
        log.info("数据库迁移完成，已应用 {} 个迁移文件", ordered.size());
    }

    private void apply(Resource resource, String name) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(connection, resource);
                try (var ps = connection.prepareStatement(
                        "INSERT INTO _migrations(name, applied_at) VALUES (?, datetime('now'))")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                connection.commit();
                log.info("已应用迁移 {}", name);
            } catch (Exception e) {
                connection.rollback();
                throw new IllegalStateException("迁移失败: " + name, e);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private String filename(Resource resource) {
        String name = resource.getFilename();
        return name == null ? resource.getDescription() : name;
    }
}
