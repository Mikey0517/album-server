package com.example.album.service;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import com.example.album.model.AppRelease;
import com.example.album.model.ReleaseManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * App 发布包管理。
 *
 * 文件系统是唯一数据源：<DATA_DIR>/releases/{metadata.json, app-vX.Y.Z.apk}。
 * 所有"读-改-写 manifest"的操作都加 synchronized，避免两个管理员同时发布时丢版本。
 */
@Service
public class AppUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AppUpdateService.class);
    private static final String METADATA = "metadata.json";
    private static final Pattern VERSION_NAME_PATTERN =
            Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$");
    private static final int MAX_NOTES_LENGTH = 20000;

    private final AppConfig cfg;
    private final ObjectMapper objectMapper;
    private final Path releasesDir;
    private final Path metadataPath;

    public AppUpdateService(AppConfig cfg, ObjectMapper objectMapper) {
        this.cfg = cfg;
        this.objectMapper = objectMapper;
        this.releasesDir = cfg.releasesDir();
        this.metadataPath = releasesDir.resolve(METADATA);
        try {
            Files.createDirectories(releasesDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建发布目录 " + releasesDir
                    + "。容器以 UID 1000 运行，请确认宿主机 data/ 已执行 "
                    + "sudo chown -R 1000:1000 data", e);
        }
    }

    /**
     * 读取清单。旧版"单个 release 对象"会自动迁移为新格式并立即落盘。
     */
    public synchronized ReleaseManifest loadManifest() {
        if (!Files.exists(metadataPath)) {
            return emptyManifest();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(metadataPath.toFile());
        } catch (Exception e) {
            log.error("发布清单损坏: {}", metadataPath, e);
            throw new ApiException(500, "发布清单损坏，请检查 data/releases/metadata.json");
        }
        if (root == null || root.isNull()) {
            return emptyManifest();
        }

        // 新格式
        if (root.has("releases") && root.get("releases").isArray()) {
            int current = readInt(root, 0, "currentVersionCode", "current_version_code");
            List<AppRelease> releases = new ArrayList<>();
            for (JsonNode node : root.get("releases")) {
                AppRelease release = parseRelease(node);
                if (release != null) {
                    releases.add(release);
                }
            }
            return new ReleaseManifest(current, sorted(releases));
        }

        // 旧格式：{"versionCode":1,...}
        if (root.has("versionCode") || root.has("version_code")) {
            AppRelease old = parseRelease(root);
            if (old != null) {
                ReleaseManifest migrated = new ReleaseManifest(old.versionCode(), List.of(old));
                writeManifest(migrated);
                log.info("已将旧版 metadata.json 迁移为新清单格式");
                return migrated;
            }
        }

        throw new ApiException(500, "发布清单格式无法识别");
    }

    public synchronized Optional<AppRelease> getLatest() {
        return Optional.ofNullable(loadManifest().current());
    }

    public synchronized List<AppRelease> getAllReleases() {
        return loadManifest().releases();
    }

    public synchronized Optional<AppRelease> findByFilename(String filename) {
        if (!isSafeFilename(filename)) {
            return Optional.empty();
        }
        return loadManifest().releases().stream()
                .filter(release -> release.filename().equals(filename))
                .findFirst();
    }

    /**
     * 上传：先写 APK，再写 manifest。manifest 失败时删除刚写入的文件。
     */
    public synchronized AppRelease saveRelease(MultipartFile file,
                                               int versionCode,
                                               String versionName,
                                               String notes) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择 APK 文件");
        }
        String original = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().trim();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw new ApiException(400, "只允许上传 .apk 文件");
        }
        if (versionCode <= 0) {
            throw new ApiException(400, "versionCode 必须大于 0");
        }
        String safeVersionName = versionName == null ? "" : versionName.trim();
        if (!VERSION_NAME_PATTERN.matcher(safeVersionName).matches()) {
            throw new ApiException(400, "versionName 只能包含字母、数字、点、下划线和短横线");
        }
        String safeNotes = notes == null ? "" : notes.trim();
        if (safeNotes.length() > MAX_NOTES_LENGTH) {
            throw new ApiException(400, "更新日志过长");
        }

        ReleaseManifest manifest = loadManifest();
        int maxCode = manifest.releases().stream()
                .mapToInt(AppRelease::versionCode)
                .max().orElse(0);
        if (versionCode <= maxCode) {
            throw new ApiException(400,
                    "versionCode 必须大于当前最大版本 " + maxCode);
        }

        String filename = "app-v" + safeVersionName + ".apk";
        Path target = releasesDir.resolve(filename).normalize();
        if (!releasesDir.equals(target.getParent()) || !isSafeFilename(filename)) {
            throw new ApiException(400, "非法的版本文件名");
        }
        boolean manifestHasFile = manifest.releases().stream()
                .anyMatch(release -> release.filename().equals(filename));
        if (Files.exists(target) || manifestHasFile) {
            throw new ApiException(400, "版本文件已存在，请更换 versionName");
        }

        writeUpload(file, target);
        try {
            String sha256 = sha256(target);
            AppRelease release = new AppRelease(
                    versionCode,
                    safeVersionName,
                    filename,
                    Files.size(target),
                    sha256,
                    safeNotes,
                    Instant.now().toString()
            );
            List<AppRelease> releases = new ArrayList<>(manifest.releases());
            releases.add(release);
            ReleaseManifest updated = new ReleaseManifest(versionCode, sorted(releases));
            writeManifest(updated);
            return release;
        } catch (RuntimeException | IOException e) {
            // 上传阶段失败：回滚刚写入的 APK，避免同名文件阻塞下一次重试。
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                log.warn("回滚上传文件失败: {}", target, cleanupError);
            }
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            log.error("保存发布版本失败", e);
            throw new ApiException(500, "保存发布版本失败");
        }
    }

    public synchronized void rollback(int versionCode) {
        ReleaseManifest manifest = loadManifest();
        AppRelease target = manifest.releases().stream()
                .filter(release -> release.versionCode() == versionCode)
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "版本不存在"));
        if (manifest.currentVersionCode() == versionCode) {
            throw new ApiException(400, "该版本已经是当前版本");
        }
        writeManifest(new ReleaseManifest(target.versionCode(), manifest.releases()));
        log.info("App 当前版本软回滚到 {} ({})", target.versionName(), target.versionCode());
    }

    public synchronized void deleteRelease(int versionCode) {
        ReleaseManifest manifest = loadManifest();
        AppRelease target = manifest.releases().stream()
                .filter(release -> release.versionCode() == versionCode)
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "版本不存在"));
        if (manifest.currentVersionCode() == versionCode) {
            throw new ApiException(400, "不能删除当前版本，请先切换到其他版本");
        }
        List<AppRelease> remaining = new ArrayList<>(manifest.releases());
        remaining.removeIf(release -> release.versionCode() == versionCode);
        // 先写 manifest：即使文件删失败也只剩孤儿文件，不影响历史列表。
        writeManifest(new ReleaseManifest(manifest.currentVersionCode(), sorted(remaining)));
        try {
            Files.deleteIfExists(releasesDir.resolve(target.filename()).normalize());
        } catch (IOException e) {
            log.warn("删除 APK 文件失败: {}", target.filename(), e);
        }
    }

    public synchronized Path fileFor(String filename) {
        if (!isSafeFilename(filename)) {
            throw new ApiException(400, "非法文件名");
        }
        AppRelease release = findByFilename(filename)
                .orElseThrow(() -> new ApiException(404, "版本不存在"));
        Path file = releasesDir.resolve(release.filename()).normalize();
        if (!releasesDir.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new ApiException(404, "安装包文件不存在");
        }
        return file;
    }

    public synchronized int currentVersionCode() {
        return loadManifest().currentVersionCode();
    }

    private void writeUpload(MultipartFile file, Path target) {
        if (file.getSize() > cfg.maxUploadSize()) {
            throw new ApiException(413, "文件超过上限");
        }
        Path temp = releasesDir.resolve(".upload-" + UUID.randomUUID() + ".part");
        long total = 0;
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > cfg.maxUploadSize()) {
                    throw new ApiException(413, "文件超过上限");
                }
                out.write(buffer, 0, read);
            }
        } catch (ApiException e) {
            deleteQuietly(temp);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("写入 APK 失败: {}", target, e);
            throw new ApiException(500, "保存 APK 失败");
        }
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("移动 APK 失败: {}", target, e);
            throw new ApiException(500, "保存 APK 失败");
        }
    }

    private void writeManifest(ReleaseManifest manifest) {
        Path temp = releasesDir.resolve(".metadata-" + UUID.randomUUID() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), manifest);
            try {
                Files.move(temp, metadataPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("写入发布清单失败", e);
            throw new ApiException(500, "写入发布清单失败");
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private AppRelease parseRelease(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        int code = readInt(node, 0, "versionCode", "version_code");
        if (code <= 0) {
            return null;
        }
        return new AppRelease(
                code,
                readString(node, "", "versionName", "version_name"),
                readString(node, "", "filename"),
                readLong(node, 0L, "fileSize", "file_size"),
                readString(node, "", "sha256"),
                readString(node, "", "releaseNotes", "release_notes"),
                readString(node, "", "publishedAt", "published_at")
        );
    }

    private int readInt(JsonNode node, int fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return fallback;
    }

    private long readLong(JsonNode node, long fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
        }
        return fallback;
    }

    private String readString(JsonNode node, String fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return fallback;
    }

    private List<AppRelease> sorted(List<AppRelease> releases) {
        List<AppRelease> copy = new ArrayList<>(releases);
        copy.sort(Comparator.comparingInt(AppRelease::versionCode).reversed()
                .thenComparing(AppRelease::versionName, Comparator.reverseOrder()));
        return List.copyOf(copy);
    }

    private ReleaseManifest emptyManifest() {
        return new ReleaseManifest(0, List.of());
    }

    private boolean isSafeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return false;
        }
        return filename.matches("[0-9A-Za-z._-]+\\.apk");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败: {}", path, e);
        }
    }
}
