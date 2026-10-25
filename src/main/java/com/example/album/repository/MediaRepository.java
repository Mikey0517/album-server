package com.example.album.repository;

import com.example.album.model.Media;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class MediaRepository {

    private static final RowMapper<Media> MAPPER = (rs, rowNum) -> {
        long postIdValue = rs.getLong("post_id");
        Long postId = rs.wasNull() ? null : postIdValue;
        int width = rs.getInt("width");
        Integer widthValue = rs.wasNull() ? null : width;
        int height = rs.getInt("height");
        Integer heightValue = rs.wasNull() ? null : height;
        long duration = rs.getLong("duration_ms");
        Long durationValue = rs.wasNull() ? null : duration;
        return new Media(
                rs.getLong("id"),
                postId,
                rs.getString("type"),
                rs.getString("path"),
                rs.getString("thumb_path"),
                widthValue,
                heightValue,
                durationValue,
                rs.getString("taken_at"),
                rs.getInt("sort_order"),
                rs.getString("created_at")
        );
    };

    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Media media) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO media(post_id, type, path, thumb_path, width, height,
                                      duration_ms, taken_at, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (media.postId() == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setLong(1, media.postId());
            }
            ps.setString(2, media.type());
            ps.setString(3, media.path());
            ps.setString(4, media.thumbPath());
            setNullableInt(ps, 5, media.width());
            setNullableInt(ps, 6, media.height());
            if (media.durationMs() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setLong(7, media.durationMs());
            }
            ps.setString(8, media.takenAt());
            ps.setInt(9, media.sortOrder());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建媒体失败：未获得主键");
        }
        return key.longValue();
    }

    public Optional<Media> findById(long id) {
        return jdbc.query("SELECT * FROM media WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Media> findByPostId(long postId) {
        return jdbc.query("SELECT * FROM media WHERE post_id = ? ORDER BY sort_order, id",
                MAPPER, postId);
    }

    public List<Media> findByPostIds(List<Long> postIds) {
        if (postIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(postIds.size(), "?"));
        return jdbc.query("SELECT * FROM media WHERE post_id IN (" + placeholders + ")"
                + " ORDER BY sort_order, id", MAPPER, postIds.toArray());
    }

    /** 朋友圈相册：所有有效动态的媒体，不受普通相册关联影响。 */
    public List<Media> feedPage(String cursorTime, Long cursorId, int limitPlusOne) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT m.* FROM media m
                JOIN posts p ON p.id = m.post_id
                WHERE m.post_id IS NOT NULL AND p.deleted_at IS NULL
                """);
        if (cursorTime != null && cursorId != null) {
            sql.append(" AND (m.taken_at < ? OR (m.taken_at = ? AND m.id < ?))");
            args.add(cursorTime);
            args.add(cursorTime);
            args.add(cursorId);
        }
        sql.append(" ORDER BY m.taken_at DESC, m.id DESC LIMIT ?");
        args.add(limitPlusOne);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<Media> findByAlbum(long albumId) {
        return jdbc.query("""
                        SELECT m.* FROM media m
                        JOIN album_media am ON am.media_id = m.id
                        WHERE am.album_id = ?
                        ORDER BY am.sort_order, am.added_at, m.id
                        """,
                MAPPER, albumId);
    }

    public void addToAlbum(long albumId, long mediaId, int sortOrder) {
        jdbc.update("""
                        INSERT OR IGNORE INTO album_media(album_id, media_id, sort_order)
                        VALUES (?, ?, ?)
                        """,
                albumId, mediaId, sortOrder);
    }

    public void removeFromAlbum(long albumId, long mediaId) {
        jdbc.update("DELETE FROM album_media WHERE album_id = ? AND media_id = ?",
                albumId, mediaId);
    }

    public boolean existsInAlbum(long albumId, long mediaId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM album_media WHERE album_id = ? AND media_id = ?",
                Integer.class, albumId, mediaId);
        return count != null && count > 0;
    }

    public int countAlbumLinks(long mediaId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM album_media WHERE media_id = ?", Integer.class, mediaId);
        return count == null ? 0 : count;
    }

    public int nextSortOrder(long albumId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), -1) FROM album_media WHERE album_id = ?",
                Integer.class, albumId);
        return max == null ? 0 : max + 1;
    }

    /** 删除相册前的候选清理集合：只处理 post_id IS NULL 的媒体。 */
    public List<Media> findOrphanCandidatesByAlbum(long albumId) {
        return jdbc.query("""
                        SELECT m.* FROM media m
                        JOIN album_media am ON am.media_id = m.id
                        WHERE am.album_id = ? AND m.post_id IS NULL
                        """,
                MAPPER, albumId);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM media WHERE id = ?", id);
    }

    public void updateThumbPath(long id, String thumbPath) {
        jdbc.update("UPDATE media SET thumb_path = ? WHERE id = ?", thumbPath, id);
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
