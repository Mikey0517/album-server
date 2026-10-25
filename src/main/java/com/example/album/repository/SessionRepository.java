package com.example.album.repository;

import com.example.album.security.CurrentUser;
import com.example.album.util.TimeUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String token, long userId, String expiresAt) {
        jdbc.update("INSERT INTO sessions(token, user_id, expires_at) VALUES (?, ?, ?)",
                token, userId, expiresAt);
    }

    /**
     * 使用与写入时相同的本地 ISO 格式比较过期时间。
     * 不能使用 datetime('now')，其空格分隔格式与 'T' 分隔格式直接字符串比较会出错。
     */
    public Optional<CurrentUser> findCurrentUser(String token) {
        String now = TimeUtil.now();
        return jdbc.query("""
                        SELECT u.id, u.user_id, u.nickname
                        FROM sessions s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.token = ? AND s.expires_at > ?
                        """,
                (rs, rowNum) -> new CurrentUser(
                        rs.getLong("id"), rs.getString("user_id"), rs.getString("nickname")),
                token, now).stream().findFirst();
    }

    public void delete(String token) {
        jdbc.update("DELETE FROM sessions WHERE token = ?", token);
    }

    public void deleteExpired() {
        jdbc.update("DELETE FROM sessions WHERE expires_at <= ?", TimeUtil.now());
    }
}
