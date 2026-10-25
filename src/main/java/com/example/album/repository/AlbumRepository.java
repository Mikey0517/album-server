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
     */
    private static final String SELECT_WITH_COVER = """
            SELECT a.id, a.title, a.cover_media_id, a.created_at,
                   CASE
                     WHEN m.thumb_path IS NOT NULL THEN m.thumb_path
                     WHEN m.type = 'image' THEN m.path
                     ELSE NULL
                   END AS cover_thumb,
                   (SELECT COUNT(*) FROM album_media am WHERE am.album_id = a.id) AS media_count
            FROM albums a
            LEFT JOIN media m ON m.id = a.cover_media_id
            """;

    private static final RowMapper<Album> MAPPER = (rs, rowNum) -> {
        long coverId = rs.getLong("cover_media_id");
        Long coverMediaId = rs.wasNull() ? null : coverId;
        return new Album(
                rs.getLong("id"),
                rs.getString("title"),
                coverMediaId,
                rs.getString("cover_thumb"),
                rs.getInt("media_count"),
                rs.getString("created_at")
        );
    };

    private final JdbcTemplate jdbc;

    public AlbumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Album> findAll() {
        return jdbc.query(SELECT_WITH_COVER + " ORDER BY a.created_at DESC, a.id DESC", MAPPER);
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

    public long insert(String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO albums(title, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, TimeUtil.now());
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
}
