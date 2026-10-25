package com.example.album.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

@Configuration
public class DataSourceConfig {

    /**
     * SQLite 单写模型：连接池固定为 1，配合 WAL 避免 database is locked。
     * journal_mode / foreign_keys / busy_timeout 都写在 JDBC URL 中，
     * 这样池中新建的每个连接都会自动生效。
     */
    @Bean
    public DataSource dataSource(AppConfig cfg) throws IOException {
        Path dataDir = cfg.dataDirPath();
        Files.createDirectories(dataDir);
        Files.createDirectories(cfg.mediaDir());
        Files.createDirectories(cfg.thumbDir());

        Path db = dataDir.resolve("app.db");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("album-sqlite");
        hikari.setJdbcUrl("jdbc:sqlite:" + db
                + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000);
        hikari.setAutoCommit(true);

        HikariDataSource ds = new HikariDataSource(hikari);
        // 主动执行一次 PRAGMA，确保库文件以 WAL 模式创建；失败则启动直接失败。
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (Exception e) {
            ds.close();
            throw new IllegalStateException("初始化 SQLite 失败", e);
        }
        return ds;
    }
}
