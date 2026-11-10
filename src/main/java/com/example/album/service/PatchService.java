package com.example.album.service;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import com.example.album.model.PatchRecord;
import com.example.album.repository.PatchRepository;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dart 热更新补丁管理。
 *
 * 文件存 <DATA_DIR>/patches/patch-v{baseVersionCode}-{patchVersion}.zip，
 * 元数据存 patches 表。同一个 base_version_code 下 patch_version 自增，
 * 不同基线的补丁互不相通（原生包升级后旧补丁不能继续用）。
 *
 * 上传是"先落盘、再写库"，写库失败时回滚刚写入的文件；
 * 同基线并发上传通过 synchronized 串行化，避免两个管理员拿到同一个补丁号。
 */
@Service
public class PatchService {

    private static final Logger log = LoggerFactory.getLogger(PatchService.class);
    private static final Pattern SAFE_FILENAME = Pattern.compile("[0-9A-Za-z._-]+\\.zip");
    private static final int MAX_NOTES_LENGTH = 20000;

    private final PatchRepository repository;
    private final AppConfig cfg;
    private final Path patchesDir;

    public PatchService(PatchRepository repository, AppConfig cfg) {
        this.repository = repository;
        this.cfg = cfg;
        this.patchesDir = cfg.patchesDir();
        try {
            Files.createDirectories(patchesDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建补丁目录 " + patchesDir
                    + "。容器以 UID 1000 运行，请确认宿主机 data/ 已执行 "
                    + "sudo chown -R 1000:1000 data", e);
        }
    }

    /**
     * 上传补丁：补丁号 = 同基线最大值 + 1，先写文件再写库，写库失败删除文件。
     */
    public synchronized PatchRecord save(MultipartFile file,
                                         int baseVersionCode,
                                         String releaseNotes) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择补丁文件");
        }
        String original = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().trim();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new ApiException(400, "只允许上传 .zip 文件");
        }
        if (baseVersionCode <= 0) {
            throw new ApiException(400, "base_version_code 必须大于 0");
        }
        String safeNotes = releaseNotes == null ? "" : releaseNotes.trim();
        if (safeNotes.length() > MAX_NOTES_LENGTH) {
            throw new ApiException(400, "更新说明过长");
        }

        int patchVersion = repository.nextPatchVersion(baseVersionCode);
        String filename = "patch-v" + baseVersionCode + "-" + patchVersion + ".zip";
        Path target = patchesDir.resolve(filename).normalize();
        if (!patchesDir.equals(target.getParent())) {
            throw new ApiException(400, "非法的补丁文件名");
        }

        writeUpload(file, target);
        try {
            String sha256 = sha256(target);
            long id = repository.insert(
                    baseVersionCode, patchVersion, filename,
                    Files.size(target), sha256, safeNotes);
            PatchRecord saved = repository.findById(id)
                    .orElseThrow(() -> new ApiException(500, "保存补丁记录失败"));
            log.info("已上传补丁 {}（基线 {}，补丁 {}，{} 字节）",
                    filename, baseVersionCode, patchVersion, saved.fileSize());
            return saved;
        } catch (RuntimeException | IOException e) {
            // 写库失败：回滚刚写入的文件，避免同名文件阻塞下一次重试。
            deleteQuietly(target);
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            log.error("保存补丁失败: {}", filename, e);
            throw new ApiException(500, "保存补丁失败");
        }
    }

    /** baseVersionCode 为 null 时返回全部，按 base_version_code / patch_version 倒序。 */
    public List<PatchRecord> list(Integer baseVersionCode) {
        return repository.findAll(baseVersionCode);
    }

    /** 只返回生效且补丁号大于 currentPatchVersion 的最大一条。 */
    public Optional<PatchRecord> latest(int baseVersionCode, int currentPatchVersion) {
        return repository.findLatest(baseVersionCode, currentPatchVersion);
    }

    public synchronized void deactivate(long id) {
        if (repository.deactivate(id) == 0) {
            throw new ApiException(404, "补丁不存在或已下线");
        }
        log.info("已下线补丁 #{}", id);
    }

    /** 删库 + 删文件。记录不存在抛 404；文件删除失败只留日志，不回滚删库。 */
    public synchronized void delete(long id) {
        PatchRecord patch = repository.findById(id)
                .orElseThrow(() -> new ApiException(404, "补丁不存在"));
        repository.delete(id);
        try {
            Files.deleteIfExists(patchesDir.resolve(patch.filename()).normalize());
        } catch (IOException e) {
            log.warn("删除补丁文件失败: {}", patch.filename(), e);
        }
        log.info("已删除补丁 #{}（{}）", id, patch.filename());
    }

    /**
     * 下载前定位文件。拒绝含 / \ .. 的文件名，防止路径穿越；
     * 文件不存在（或已被删除）抛 404。
     */
    public Path fileFor(String filename) {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")
                || !SAFE_FILENAME.matcher(filename).matches()) {
            throw new ApiException(400, "非法文件名");
        }
        Path file = patchesDir.resolve(filename).normalize();
        if (!patchesDir.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new ApiException(404, "补丁文件不存在");
        }
        return file;
    }

    private void writeUpload(MultipartFile file, Path target) {
        if (file.getSize() > cfg.maxUploadSize()) {
            throw new ApiException(413, "文件超过上限");
        }
        Path temp = patchesDir.resolve(".upload-" + UUID.randomUUID() + ".part");
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
            log.error("写入补丁失败: {}", target, e);
            throw new ApiException(500, "保存补丁失败");
        }
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("移动补丁失败: {}", target, e);
            throw new ApiException(500, "保存补丁失败");
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

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败: {}", path, e);
        }
    }
}
