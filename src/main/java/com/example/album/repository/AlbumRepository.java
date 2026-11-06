package com.example.album.repository;

import com.example.album.model.Album;
import com.example.album.util.TimeUtil;
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
public class AlbumRepository {

    /**
     * cover_thumb 优先使用媒体缩略图；图片没有缩略图时退回原图；视频没有缩略图则为 null。
     * owner_nickname 与 share_count 用于相册列表展示「私人 / 我创建的 / 分享给我」。
     */
    private static final String SELECT_WITH_COVER = """
            SELECT a.id, a.title, a.cover_media_id, a.created_at, a.owner_id, a.visibility,
                   u.nickname AS owner_nickname,
                   CASE
                     WHEN m.thumb_path IS NOT NULL THEN m.thumb_path
                     WHEN m.type = 'image' THEN m.path
                     ELSE NULL
                   END AS cover_thumb,
                   (SELECT COUNT(*) FROM album_media am WHERE am.album_id = a.id) AS media_count,
                   (SELECT COUNT(*) FROM album_shares sh WHERE sh.album_id = a.id) AS share_count
            FROM albums a
            LEFT JOIN media m ON m.id = a.cover_media_id
            LEFT JOIN users u ON u.id = a.owner_id
            """;

    private static final RowMapper<Album> MAPPER = (rs, rowNum) -> {
        long coverId = rs.getLong("cover_media_id");
        Long coverMediaId = rs.wasNull() ? null : coverId;
        long ownerId = rs.getLong("owner_id");
        Long owner = rs.wasNull() ? null : ownerId;
        return new Album(
                rs.getLong("id"),
                rs.getString("title"),
                coverMediaId,
                rs.getString("cover_thumb"),
                rs.getInt("media_count"),
                rs.getString("created_at"),
                owner,
                rs.getString("owner_nickname"),
                rs.getString("visibility"),
                rs.getInt("share_count")
        );
    };

    private final JdbcTemplate jdbc;

    public AlbumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Album> findAll() {
        return jdbc.query(SELECT_WITH_COVER + " ORDER BY a.created_at DESC, a.id DESC", MAPPER);
    }

    /** 家庭公共相册 + 自己创建的私人相册 + 别人分享给我的私人相册。 */
    public List<Album> findVisibleTo(long userId) {
        return jdbc.query(SELECT_WITH_COVER + """
                 WHERE a.visibility = 'family'
                    OR a.owner_id = ?
                    OR EXISTS (SELECT 1 FROM album_shares s
                               WHERE s.album_id = a.id AND s.user_id = ?)
                 ORDER BY a.created_at DESC, a.id DESC
                """, MAPPER, userId, userId);
    }

    public Optional<Album> findById(long id) {
        return jdbc.query(SELECT_WITH_COVER + " WHERE a.id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public boolean exists(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM albums WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    public boolean canView(long albumId, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM albums a
                 WHERE a.id = ?
                   AND (a.visibility = 'family'
                        OR a.owner_id = ?
                        OR EXISTS (SELECT 1 FROM album_shares s
                                   WHERE s.album_id = a.id AND s.user_id = ?))
                """, Integer.class, albumId, userId, userId);
        return count != null && count > 0;
    }

    public long insert(String title, Long ownerId, String visibility) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO albums(title, created_at, owner_id, visibility) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, TimeUtil.now());
            if (ownerId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, ownerId);
            }
            ps.setString(4, visibility);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建相册失败：未获得主键");
        }
        return key.longValue();
    }

    public void updateTitle(long id, String title) {
        jdbc.update("UPDATE albums SET title = ? WHERE id = ?", title, id);
    }

    public void updateCover(long id, long mediaId) {
        jdbc.update("UPDATE albums SET cover_media_id = ? WHERE id = ?", mediaId, id);
    }

    public void clearCoverIfMatches(long albumId, long mediaId) {
        jdbc.update("UPDATE albums SET cover_media_id = NULL WHERE id = ? AND cover_media_id = ?",
                albumId, mediaId);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM albums WHERE id = ?", id);
    }

    // ---- 分享 ----

    public void addShare(long albumId, long userId) {
        jdbc.update("INSERT OR IGNORE INTO album_shares(album_id, user_id) VALUES (?, ?)",
                albumId, userId);
    }

    public void removeShare(long albumId, long userId) {
        jdbc.update("DELETE FROM album_shares WHERE album_id = ? AND user_id = ?",
                albumId, userId);
    }

    /** 分享名单：返回 (user_id 数字主键, 登录 ID, 昵称)。 */
    public List<long[]> findShareUserIds(long albumId) {
        return jdbc.query("SELECT user_id FROM album_shares WHERE album_id = ? ORDER BY created_at",
                (rs, rowNum) -> new long[]{rs.getLong(1)}, albumId);
    }

    public List<com.example.album.model.User> findSharedUsers(long albumId) {
        return jdbc.query("""
                SELECT u.* FROM album_shares s
                  JOIN users u ON u.id = s.user_id
                 WHERE s.album_id = ?
                 ORDER BY s.created_at
                """, (rs, rowNum) -> new com.example.album.model.User(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                rs.getString("created_at")
        ), albumId);
    }
}
