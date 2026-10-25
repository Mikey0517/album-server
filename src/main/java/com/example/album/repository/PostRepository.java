package com.example.album.repository;

import com.example.album.model.Post;
import com.example.album.util.TimeUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PostRepository {

    private static final RowMapper<Post> MAPPER = (rs, rowNum) -> new Post(
            rs.getLong("id"),
            rs.getLong("author_id"),
            rs.getString("author_nickname"),
            rs.getString("content"),
            rs.getString("posted_at"),
            rs.getString("deleted_at")
    );

    private static final String BASE_SELECT = """
            SELECT p.id, p.author_id, u.nickname AS author_nickname,
                   p.content, p.posted_at, p.deleted_at
            FROM posts p
            JOIN users u ON u.id = p.author_id
            """;

    private final JdbcTemplate jdbc;

    public PostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long authorId, String content, String postedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO posts(author_id, content, posted_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setString(2, content);
            ps.setString(3, postedAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建动态失败：未获得主键");
        }
        return key.longValue();
    }

    /**
     * 游标分页：按 (posted_at, id) 降序，翻页时取严格小于游标的记录。
     * 不用 OFFSET，避免新增/删除导致重复或漏项。
     */
    public List<Post> page(String cursorTime, Long cursorId, int limitPlusOne) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(BASE_SELECT)
                .append(" WHERE p.deleted_at IS NULL");
        if (cursorTime != null && cursorId != null) {
            sql.append(" AND (p.posted_at < ? OR (p.posted_at = ? AND p.id < ?))");
            args.add(cursorTime);
            args.add(cursorTime);
            args.add(cursorId);
        }
        sql.append(" ORDER BY p.posted_at DESC, p.id DESC LIMIT ?");
        args.add(limitPlusOne);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<Post> findById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE p.id = ? AND p.deleted_at IS NULL",
                MAPPER, id).stream().findFirst();
    }

    /** 返回受影响行数，Service 用它区分 404 / 403。 */
    public int softDelete(long id, long authorId) {
        return jdbc.update(
                "UPDATE posts SET deleted_at = ? WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                TimeUtil.now(), id, authorId);
    }

    public boolean existsActive(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE id = ? AND deleted_at IS NULL",
                Integer.class, id);
        return count != null && count > 0;
    }

    public Long findAuthorId(long id) {
        return jdbc.query("SELECT author_id FROM posts WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getLong("author_id") : null, id);
    }
}
