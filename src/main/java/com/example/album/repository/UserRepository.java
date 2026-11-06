package com.example.album.repository;

import com.example.album.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final RowMapper<User> MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("user_id"),
            rs.getString("nickname"),
            rs.getString("password_hash"),
            rs.getString("created_at")
    );

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUserId(String userId) {
        return jdbc.query("SELECT * FROM users WHERE user_id = ?", MAPPER, userId)
                .stream().findFirst();
    }

    public Optional<User> findById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public boolean existsByUserId(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    /** 昵称唯一（忽略大小写）；excludeUserId 用于「修改昵称时不和自己冲突」。 */
    public boolean existsByNickname(String nickname, Long excludeUserId) {
        Integer count = excludeUserId == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE nickname = ? COLLATE NOCASE",
                        Integer.class, nickname)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE nickname = ? COLLATE NOCASE AND id <> ?",
                        Integer.class, nickname, excludeUserId);
        return count != null && count > 0;
    }

    /** 按登录 ID 或昵称模糊搜索，用于分享相册时找人。 */
    public List<User> search(String keyword, long excludeUserId, int limit) {
        String like = "%" + keyword.replace("%", "").replace("_", "") + "%";
        return jdbc.query("""
                SELECT * FROM users
                 WHERE id <> ?
                   AND (user_id LIKE ? COLLATE NOCASE OR nickname LIKE ? COLLATE NOCASE)
                 ORDER BY CASE WHEN user_id = ? COLLATE NOCASE OR nickname = ? COLLATE NOCASE
                               THEN 0 ELSE 1 END,
                          user_id
                 LIMIT ?
                """, MAPPER, excludeUserId, like, like, keyword, keyword, limit);
    }

    public void updateNickname(long id, String nickname) {
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, id);
    }

    public long insert(String userId, String nickname, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users(user_id, nickname, password_hash) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userId);
            ps.setString(2, nickname);
            ps.setString(3, passwordHash);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建用户失败：未获得主键");
        }
        return key.longValue();
    }
}
