package com.example.album.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.example.album.config.AppConfig;
import com.example.album.dto.MediaResponse;
import com.example.album.exception.ApiException;
import com.example.album.model.Media;
import com.example.album.repository.MediaRepository;
import com.example.album.util.TimeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 媒体落盘、EXIF/ffprobe 元数据解析、缩略图生成与物理删除。
 * 所有 ffmpeg/ffprobe 调用都有 60 秒硬超时。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final long FFMPEG_TIMEOUT_SECONDS = 60;
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp");
    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");

    private final AppConfig cfg;
    private final MediaRepository mediaRepository;
    private final ObjectMapper objectMapper;

    public MediaService(AppConfig cfg,
                        MediaRepository mediaRepository,
                        ObjectMapper objectMapper) {
        this.cfg = cfg;
        this.mediaRepository = mediaRepository;
        this.objectMapper = objectMapper;
    }

    /** 解析 + 落盘 + 缩略图，但尚未写数据库。 */
    public record MediaResult(
            String type,
            String path,
            String thumbPath,
            Integer width,
            Integer height,
            Long durationMs,
            String takenAt
    ) {
    }

    /**
     * 完整处理一个上传文件并写入 media 表。
     *
     * @param postId    朋友圈动态 id；普通相册上传时为 null
     * @param sortOrder 动态内或相册内的展示顺序
     */
    public Media store(MultipartFile file, Long postId, int sortOrder) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "上传文件为空");
        }
        if (file.getSize() > cfg.maxUploadSize()) {
            throw new ApiException(413, "文件超过上限");
        }
        String hash = sha256(file);
        Optional<Media> existing = mediaRepository.findByHash(hash);
        if (existing.isPresent()) {
            Media source = existing.get();
            // 文件已存在：只新增一条引用记录，共享原图和缩略图，不重复落盘。
            Media media = new Media(
                    0,
                    postId,
                    source.type(),
                    source.path(),
                    source.thumbPath(),
                    source.width(),
                    source.height(),
                    source.durationMs(),
                    source.takenAt(),
                    sortOrder,
                    null,
                    hash
            );
            long id = mediaRepository.insert(media);
            return new Media(id, postId, media.type(), media.path(), media.thumbPath(),
                    media.width(), media.height(), media.durationMs(), media.takenAt(),
                    sortOrder, null, hash);
        }

        MediaResult result = process(file, postId);
        Media media = new Media(
                0,
                postId,
                result.type(),
                result.path(),
                result.thumbPath(),
                result.width(),
                result.height(),
                result.durationMs(),
                result.takenAt(),
                sortOrder,
                null,
                hash
        );
        long id = mediaRepository.insert(media);
        return new Media(id, postId, media.type(), media.path(), media.thumbPath(),
                media.width(), media.height(), media.durationMs(), media.takenAt(),
                sortOrder, null, hash);
    }

    public MediaResult process(MultipartFile file, Long postId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "上传文件为空");
        }
        String ext = extensionOf(file.getOriginalFilename());
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);

        boolean videoByExt = VIDEO_EXTENSIONS.contains(ext);
        boolean imageByExt = IMAGE_EXTENSIONS.contains(ext);
        boolean video = videoByExt || (!imageByExt && contentType.startsWith("video/"));
        boolean image = imageByExt || (!videoByExt && contentType.startsWith("image/"));

        if (!video && !image) {
            throw new ApiException(400, "不支持的文件类型，仅支持常见图片和视频");
        }
        if (ext.isBlank()) {
            ext = video ? "mp4" : "jpg";
        }
        if (video && !VIDEO_EXTENSIONS.contains(ext)) {
            ext = "mp4";
        }
        if (image && !IMAGE_EXTENSIONS.contains(ext)) {
            throw new ApiException(400, "暂不支持该图片格式，请转换为 JPG/PNG 后上传");
        }

        String uid = UUID.randomUUID().toString().replace("-", "");
        Path raw = cfg.mediaDir().resolve(uid + "." + ext);
        saveWithLimit(file, raw);

        try {
            return video ? processVideo(raw, uid) : processImage(raw, uid);
        } catch (ApiException e) {
            // 元数据解析失败时不要留下孤儿原文件。
            deleteQuietly(raw);
            throw e;
        } catch (Exception e) {
            deleteQuietly(raw);
            log.error("处理媒体失败 postId={}", postId, e);
            throw new ApiException(500, "处理媒体失败");
        }
    }

    private MediaResult processImage(Path raw, String uid) throws IOException {
        String takenAt = TimeUtil.now();
        Integer width = null;
        Integer height = null;

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(raw.toFile());
            ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exif != null) {
                String original = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (original == null) {
                    original = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
                }
                String parsed = TimeUtil.fromExif(original);
                if (parsed != null) {
                    takenAt = parsed;
                }
            }
        } catch (Exception e) {
            // 没有 EXIF 或格式不支持都不应导致上传失败，直接回退上传时间。
            log.debug("读取图片 EXIF 失败: {}", raw.getFileName());
        }

        BufferedImage image = null;
        try {
            image = ImageIO.read(raw.toFile());
            if (image == null) {
                throw new ApiException(400, "无法解析图片，请上传 JPG/PNG/WebP 格式");
            }
            width = image.getWidth();
            height = image.getHeight();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "无法解析图片，请上传 JPG/PNG/WebP 格式");
        }

        Path thumb = cfg.thumbDir().resolve(uid + "_t.jpg");
        try {
            Thumbnails.of(raw.toFile())
                    .useExifOrientation(true)
                    .size(400, 400)
                    .outputFormat("jpg")
                    .outputQuality(0.8f)
                    .toFile(thumb.toFile());
        } catch (Exception e) {
            log.warn("生成图片缩略图失败: {}", raw.getFileName(), e);
            // 没有缩略图时客户端可以回退使用原图，不让整个上传失败。
            thumb = null;
        } finally {
            if (image != null) {
                image.flush();
            }
        }

        return new MediaResult(
                "image",
                "/files/" + raw.getFileName(),
                thumb == null ? null : "/files/thumbs/" + thumb.getFileName(),
                width,
                height,
                null,
                takenAt
        );
    }

    private MediaResult processVideo(Path raw, String uid) throws IOException {
        String takenAt = TimeUtil.now();
        Long durationMs = null;
        Integer width = null;
        Integer height = null;

        try {
            String json = runProcess(List.of(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", raw.toString()), "ffprobe");
            JsonNode root = objectMapper.readTree(json);
            JsonNode format = root.path("format");
            double durationSeconds = format.path("duration").asDouble(0);
            if (durationSeconds > 0) {
                durationMs = Math.round(durationSeconds * 1000);
            }
            String creationTime = format.path("tags").path("creation_time").asText(null);
            String parsed = TimeUtil.fromCreationTime(creationTime);
            if (parsed != null) {
                takenAt = parsed;
            }
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    if (stream.hasNonNull("width")) {
                        width = stream.get("width").asInt();
                    }
                    if (stream.hasNonNull("height")) {
                        height = stream.get("height").asInt();
                    }
                    break;
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ffprobe 解析失败: {}", raw.getFileName(), e);
            throw new ApiException(400, "无法解析视频文件");
        }

        Path thumb = cfg.thumbDir().resolve(uid + "_t.jpg");
        try {
            runProcess(List.of(
                    "ffmpeg", "-y", "-loglevel", "error", "-ss", "0",
                    "-i", raw.toString(), "-vframes", "1",
                    "-vf", "scale=400:-2", thumb.toString()), "ffmpeg");
            if (!Files.exists(thumb) || Files.size(thumb) == 0) {
                deleteQuietly(thumb);
                thumb = null;
            }
        } catch (Exception e) {
            log.warn("抽取视频首帧失败: {}", raw.getFileName(), e);
            deleteQuietly(thumb);
            thumb = null;
        }

        return new MediaResult(
                "video",
                "/files/" + raw.getFileName(),
                thumb == null ? null : "/files/thumbs/" + thumb.getFileName(),
                width,
                height,
                durationMs,
                takenAt
        );
    }

    /**
     * 分块写盘，累计字节数超过限制立即删除并抛 413，避免把超大文件整个读入内存。
     */
    private void saveWithLimit(MultipartFile file, Path target) throws IOException {
        if (file.getSize() > cfg.maxUploadSize()) {
            throw new ApiException(413, "文件超过上限");
        }
        long total = 0;
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > cfg.maxUploadSize()) {
                    out.close();
                    deleteQuietly(target);
                    throw new ApiException(413, "文件超过上限");
                }
                out.write(buffer, 0, read);
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    /**
     * 运行外部命令并消费输出。必须同时读取 stdout/stderr，否则管道写满会让进程卡死。
     */
    private String runProcess(List<String> command, String name) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (output) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                log.debug("读取 {} 输出结束: {}", name, e.getMessage());
            }
        }, name + "-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new ApiException(500, name + " 超时");
            }
            reader.join(5000);
            if (process.exitValue() != 0) {
                String message;
                synchronized (output) {
                    message = output.toString();
                }
                log.warn("{} 退出码 {}: {}", name, process.exitValue(), message);
                throw new ApiException(400, name + " 处理失败");
            }
            synchronized (output) {
                return output.toString();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ApiException(500, name + " 被中断");
        }
    }

    /**
     * 删除一条媒体引用记录。只有同一物理文件没有任何其他引用记录时，才删除磁盘文件。
     */
    public void deleteFilesAndRecord(Media media) {
        mediaRepository.deleteById(media.id());
        if (mediaRepository.countByPath(media.path()) == 0) {
            deleteMediaFile(media.path());
            deleteMediaFile(media.thumbPath());
        }
    }

    /** 为历史媒体补 hash；失败时保留 null，不影响启动。 */
    public String ensureHash(Media media) {
        if (media.contentHash() != null && !media.contentHash().isBlank()) {
            return media.contentHash();
        }
        Path file = resolveMediaPath(media.path());
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String hash = sha256(file);
            mediaRepository.updateHash(media.id(), hash);
            return hash;
        } catch (IOException e) {
            log.warn("计算媒体 hash 失败: {}", media.path(), e);
            return null;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleHashBackfill() {
        Thread thread = new Thread(this::backfillMissingHashes, "media-hash-backfill");
        thread.setDaemon(true);
        thread.start();
    }

    /** 历史数据补 hash；后台执行，避免影响启动。 */
    public void backfillMissingHashes() {
        List<Media> missing;
        try {
            missing = mediaRepository.findWithoutHash();
        } catch (Exception e) {
            log.warn("查询待补 hash 媒体失败", e);
            return;
        }
        int updated = 0;
        for (Media media : missing) {
            if (ensureHash(media) != null) {
                updated++;
            }
        }
        if (updated > 0) {
            log.info("已为 {} 个历史媒体补充 hash", updated);
        }
    }

    public void deleteMediaFile(String urlPath) {
        Path target = resolveMediaPath(urlPath);
        if (target != null) {
            deleteQuietly(target);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", path, e);
        }
    }

    public MediaResponse toResponse(Media media) {
        return new MediaResponse(
                media.id(),
                media.type(),
                media.thumbPath(),
                media.path(),
                media.width(),
                media.height(),
                media.durationMs(),
                media.takenAt(),
                media.createdAt(),
                media.postId()
        );
    }

    private String sha256(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Path resolveMediaPath(String urlPath) {
        if (urlPath == null || !urlPath.startsWith("/files/")) {
            return null;
        }
        String relative = urlPath.substring("/files/".length());
        Path target = cfg.mediaDir().resolve(relative).normalize();
        if (!target.startsWith(cfg.mediaDir().toAbsolutePath().normalize())) {
            return null;
        }
        return target;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : "";
    }
}
