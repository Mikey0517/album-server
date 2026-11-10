package com.example.album.repository;

import com.example.album.model.PatchRecord;
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
public class PatchRepository {

    private static final RowMapper<PatchRecord> MAPPER = (rs, rowNum) -> new PatchRecord(
            rs.getLong("id"),
            rs.getInt("base_version_code"),
            rs.getInt("patch_version"),
            rs.getString("filename"),
            rs.getLong("file_size"),
            rs.getString("sha256"),
            rs.getString("release_notes"),
            rs.getInt("is_active") != 0,
            rs.getString("created_at")
    );

    private static final String BASE_SELECT = """
            SELECT id, base_version_code, patch_version, filename, file_size,
                   sha256, release_notes, is_active, created_at
            FROM patches
            """;

    private final JdbcTemplate jdbc;

    public PatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同基线下的下一个补丁号：当前最大值 + 1。 */
    public int nextPatchVersion(int baseVersionCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(patch_version), 0) FROM patches WHERE base_version_code = ?",
                Integer.class, baseVersionCode);
        return (max == null ? 0 : max) + 1;
    }

    public long insert(int baseVersionCode, int patchVersion, String filename,
                       long fileSize, String sha256, String releaseNotes) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO patches(base_version_code, patch_version, filename,
                                        file_size, sha256, release_notes, is_active)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, baseVersionCode);
            ps.setInt(2, patchVersion);
            ps.setString(3, filename);
            ps.setLong(4, fileSize);
            ps.setString(5, sha256);
            ps.setString(6, releaseNotes);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建补丁记录失败：未获得主键");
        }
        return key.longValue();
    }

    public Optional<PatchRecord> findById(long id) {
        return jdbc.query(BASE_SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** baseVersionCode 为 null 时返回全部；按基线、补丁号倒序。 */
    public List<PatchRecord> findAll(Integer baseVersionCode) {
        List<Object> args = new ArrayList<>();
        String sql = BASE_SELECT;
        if (baseVersionCode != null) {
            sql += " WHERE base_version_code = ?";
            args.add(baseVersionCode);
        }
        sql += " ORDER BY base_version_code DESC, patch_version DESC";
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    /** 只取生效且补丁号大于当前值的最大一条。 */
    public Optional<PatchRecord> findLatest(int baseVersionCode, int currentPatchVersion) {
        return jdbc.query(BASE_SELECT + """
                         WHERE base_version_code = ?
                           AND is_active = 1
                           AND patch_version > ?
                         ORDER BY patch_version DESC
                         LIMIT 1
                         """, MAPPER, baseVersionCode, currentPatchVersion)
                .stream().findFirst();
    }

    /** 返回受影响行数，Service 用它区分 404。 */
    public int deactivate(long id) {
        return jdbc.update("UPDATE patches SET is_active = 0 WHERE id = ? AND is_active = 1", id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM patches WHERE id = ?", id);
    }
}
