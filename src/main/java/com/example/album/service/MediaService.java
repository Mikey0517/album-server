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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体落盘、EXIF/ffprobe 元数据解析、动态照片解析、缩略图生成与物理删除。
 *
 * 原则：上传原始文件按字节原样保存；缩略图统一由 ffmpeg 生成，
 * 以兼容 HEIC/HEIF/AVIF/GIF 等 ImageIO 无法解码的格式。
 * 所有 ffmpeg/ffprobe 调用都有 60 秒硬超时。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final long FFMPEG_TIMEOUT_SECONDS = 60;
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif");
    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "mov", "m4v", "webm", "mkv", "avi");
    private static final int XMP_SCAN_BYTES = 512 * 1024;

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

    public record MediaResult(
            String type,
            String path,
            String thumbPath,
            Integer width,
            Integer height,
            Long durationMs,
            String takenAt,
            String liveVideoPath,
            String originalFormat,
            boolean thumbFailed
    ) {
    }

    public record ProbeInfo(
            Integer width,
            Integer height,
            Long durationMs,
            String takenAt
    ) {
    }

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
            // 文件已存在：只新增一条引用记录，共享原图/缩略图/动态视频，不重复落盘。
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
                    hash,
                    source.liveVideoPath(),
                    source.originalFormat(),
                    source.thumbFailed()
            );
            long id = mediaRepository.insert(media);
            return new Media(id, postId, media.type(), media.path(), media.thumbPath(),
                    media.width(), media.height(), media.durationMs(), media.takenAt(),
                    sortOrder, null, hash, media.liveVideoPath(), media.originalFormat(),
                    media.thumbFailed());
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
                hash,
                result.liveVideoPath(),
                result.originalFormat(),
                result.thumbFailed()
        );
        try {
            long id = mediaRepository.insert(media);
            return new Media(id, postId, media.type(), media.path(), media.thumbPath(),
                    media.width(), media.height(), media.durationMs(), media.takenAt(),
                    sortOrder, null, hash, media.liveVideoPath(), media.originalFormat(),
                    media.thumbFailed());
        } catch (RuntimeException e) {
            deleteMediaFile(media.path());
            deleteMediaFile(media.thumbPath());
            deleteMediaFile(media.liveVideoPath());
            throw e;
        }
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
            throw new ApiException(400,
                    "暂不支持该图片格式，支持 JPEG/PNG/GIF/WebP/HEIC/HEIF/AVIF/BMP");
        }

        String uid = UUID.randomUUID().toString().replace("-", "");
        Path raw = cfg.mediaDir().resolve(uid + "." + ext);
        saveWithLimit(file, raw);

        try {
            return video ? processVideo(raw, uid, ext) : processImage(raw, uid, ext);
        } catch (ApiException e) {
            deleteArtifacts(raw, uid);
            throw e;
        } catch (Exception e) {
            deleteArtifacts(raw, uid);
            log.error("处理媒体失败 postId={} ext={}", postId, ext, e);
            throw new ApiException(500, "处理媒体失败");
        }
    }

    private MediaResult processImage(Path raw, String uid, String ext) {
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
            log.debug("读取图片 EXIF 失败: {}", raw.getFileName());
        }

        // HEIC/HEIF 通常不在 ffmpeg 的默认解码器里；只把临时转码出的
        // JPEG 用于读取尺寸/生成缩略图，原始 HEIC 文件保持不动。
        Path preview = raw;
        boolean tempPreview = false;
        if ("heic".equals(ext) || "heif".equals(ext)) {
            Path converted = cfg.thumbDir().resolve(uid + "_heic_src.jpg");
            try {
                runProcess(List.of(
                        "heif-convert", raw.toString(), converted.toString()),
                        "heif-convert");
                if (Files.exists(converted) && Files.size(converted) > 0) {
                    preview = converted;
                    tempPreview = true;
                }
            } catch (Exception e) {
                log.warn("HEIC 临时解码失败: {}", raw.getFileName(), e);
            }
        }

        ProbeInfo probe = probe(preview);
        if (probe != null) {
            width = probe.width();
            height = probe.height();
            if (probe.takenAt() != null) {
                takenAt = probe.takenAt();
            }
        }

        String liveVideoPath = null;
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            liveVideoPath = extractLiveVideo(raw, uid);
        } else {
            log.debug("非 JPEG 动态照片暂按静态图处理: {}", raw.getFileName());
        }

        String thumbPath = generateThumbnail(preview, uid, "/files/thumbs/");
        if (tempPreview) {
            deleteQuietly(preview);
        }
        boolean thumbFailed = thumbPath == null;
        return new MediaResult(
                "image",
                "/files/" + raw.getFileName(),
                thumbPath,
                width,
                height,
                null,
                takenAt,
                liveVideoPath,
                normalizeFormat(ext),
                thumbFailed
        );
    }

    private MediaResult processVideo(Path raw, String uid, String ext) {
        ProbeInfo probe = probe(raw);
        if (probe == null) {
            throw new ApiException(400, "无法解析视频文件");
        }
        String thumbPath = generateThumbnail(raw, uid, "/files/thumbs/");
        boolean thumbFailed = thumbPath == null;
        return new MediaResult(
                "video",
                "/files/" + raw.getFileName(),
                thumbPath,
                probe.width(),
                probe.height(),
                probe.durationMs(),
                probe.takenAt() == null ? TimeUtil.now() : probe.takenAt(),
                null,
                normalizeFormat(ext),
                thumbFailed
        );
    }

    private ProbeInfo probe(Path raw) {
        try {
            String json = runProcess(List.of(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", raw.toString()), "ffprobe");
            JsonNode root = objectMapper.readTree(json);
            JsonNode format = root.path("format");
            double durationSeconds = format.path("duration").asDouble(0);
            Long durationMs = durationSeconds > 0
                    ? Math.round(durationSeconds * 1000) : null;
            String creationTime = format.path("tags").path("creation_time").asText(null);
            String takenAt = TimeUtil.fromCreationTime(creationTime);
            Integer width = null;
            Integer height = null;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    if (stream.hasNonNull("width")) width = stream.get("width").asInt();
                    if (stream.hasNonNull("height")) height = stream.get("height").asInt();
                    break;
                }
            }
            return new ProbeInfo(width, height, durationMs, takenAt);
        } catch (Exception e) {
            log.warn("ffprobe 解析失败: {}", raw.getFileName(), e);
            return null;
        }
    }

    private String generateThumbnail(Path raw, String uid, String prefix) {
        Path thumb = cfg.thumbDir().resolve(uid + "_t.jpg");
        try {
            runProcess(List.of(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", raw.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=400:400:force_original_aspect_ratio=decrease",
                    "-q:v", "3",
                    thumb.toString()), "ffmpeg");
            if (!Files.exists(thumb) || Files.size(thumb) == 0) {
                deleteQuietly(thumb);
                return null;
            }
            return prefix + thumb.getFileName();
        } catch (Exception e) {
            log.warn("生成缩略图失败: {}", raw.getFileName(), e);
            deleteQuietly(thumb);
            return null;
        }
    }

    /**
     * 解析 Google Motion Photo v2/v3 与 MicroVideo v1，提取内嵌 MP4。
     * 第一版仅对 JPEG 容器提取；HEIC 动态照片降级为静态图。
     */
    private String extractLiveVideo(Path raw, String uid) {
        try {
            long size = Files.size(raw);
            LiveMeta meta = scanLiveMeta(raw);
            if (!meta.motionPhoto && !meta.microVideo) return null;

            long start = -1;
            if (meta.microVideo && meta.microVideoOffset != null) {
                start = size - meta.microVideoOffset;
            } else if (meta.motionPhoto && meta.motionVideoLength != null) {
                start = size - meta.motionVideoLength;
            } else if (meta.motionPhoto) {
                long eoi = findJpegEoiEnd(raw);
                if (eoi > 0) start = findMp4Start(raw, eoi);
            }
            if (start < 0 || start >= size) {
                log.warn("动态照片视频偏移无效: {} start={} size={}", raw, start, size);
                return null;
            }

            Path video = cfg.mediaDir().resolve(uid + "_live.mp4");
            copyRange(raw, video, start, size);
            ProbeInfo probe = probe(video);
            if (probe == null || probe.durationMs() == null || probe.durationMs() <= 0) {
                log.warn("提取的动态视频无效: {}", video);
                deleteQuietly(video);
                return null;
            }
            String relative = "/files/" + video.getFileName();
            log.info("提取动态照片视频: {} → {} ({} ms)",
                    raw, video, probe.durationMs());
            return relative;
        } catch (Exception e) {
            log.warn("动态照片视频提取失败: {}", raw, e);
            return null;
        }
    }

    private LiveMeta scanLiveMeta(Path raw) throws IOException {
        String head;
        try (InputStream in = Files.newInputStream(raw)) {
            byte[] buf = new byte[(int) Math.min(XMP_SCAN_BYTES, Files.size(raw))];
            int total = 0;
            int read;
            while (total < buf.length
                    && (read = in.read(buf, total, buf.length - total)) != -1) {
                total += read;
            }
            head = new String(buf, 0, total, StandardCharsets.ISO_8859_1);
        }
        boolean micro = Pattern.compile(
                "(?:[A-Za-z0-9_]+:)?MicroVideo\\s*=\\s*[\"']1[\"']")
                .matcher(head).find();
        boolean motion = Pattern.compile(
                "(?:[A-Za-z0-9_]+:)?MotionPhoto\\s*=\\s*[\"']1[\"']")
                .matcher(head).find();
        Long microOffset = regexLong(head,
                "(?:[A-Za-z0-9_]+:)?MicroVideoOffset\\s*=\\s*[\"'](\\d+)[\"']");
        Long motionLength = regexLong(head,
                "(?:[A-Za-z0-9_]+:)?MotionPhotoVideoLength\\s*=\\s*[\"'](\\d+)[\"']");
        return new LiveMeta(micro, motion, microOffset, motionLength);
    }

    private Long regexLong(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long findJpegEoiEnd(Path raw) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(raw.toFile(), "r")) {
            long size = file.length();
            if (size < 4) return -1;
            file.seek(0);
            if (file.readUnsignedByte() != 0xFF || file.readUnsignedByte() != 0xD8) {
                return -1;
            }
            long pos = 2;
            while (pos + 3 < size) {
                file.seek(pos);
                int b = file.readUnsignedByte();
                if (b != 0xFF) {
                    pos++;
                    continue;
                }
                int marker = file.readUnsignedByte();
                while (marker == 0xFF) {
                    marker = file.readUnsignedByte();
                    pos++;
                }
                pos += 2;
                if (marker == 0xD9) return pos;
                if (marker == 0xDA) {
                    long p = pos;
                    file.seek(p);
                    int prev = 0;
                    while (p < size) {
                        int cur = file.readUnsignedByte();
                        p++;
                        if (prev == 0xFF && cur == 0xD9) return p;
                        prev = cur;
                    }
                    return -1;
                }
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    continue;
                }
                if (pos + 2 > size) return -1;
                file.seek(pos);
                int len = (file.readUnsignedByte() << 8) | file.readUnsignedByte();
                if (len < 2) return -1;
                pos += len;
            }
            return -1;
        }
    }

    private long findMp4Start(Path raw, long from) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(raw.toFile(), "r")) {
            long size = file.length();
            long end = Math.min(size, from + 2L * 1024 * 1024);
            int length = (int) (end - from);
            if (length < 8) return -1;
            file.seek(from);
            byte[] data = new byte[length];
            file.readFully(data);
            for (int i = 0; i + 8 <= data.length; i++) {
                if (data[i + 4] == 'f' && data[i + 5] == 't'
                        && data[i + 6] == 'y' && data[i + 7] == 'p') {
                    return from + i;
                }
            }
            return -1;
        }
    }

    private void copyRange(Path source, Path target, long start, long end)
            throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(source.toFile(), "r");
             OutputStream out = Files.newOutputStream(target)) {
            in.seek(start);
            long remaining = end - start;
            byte[] buffer = new byte[1024 * 1024];
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void deleteArtifacts(Path raw, String uid) {
        deleteQuietly(raw);
        deleteQuietly(cfg.thumbDir().resolve(uid + "_t.jpg"));
        deleteQuietly(cfg.mediaDir().resolve(uid + "_live.mp4"));
    }

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
        if (media.liveVideoPath() != null
                && mediaRepository.countByPath(media.liveVideoPath()) == 0) {
            deleteMediaFile(media.liveVideoPath());
        }
    }

    public String ensureHash(Media media) {
        if (media.contentHash() != null && !media.contentHash().isBlank()) {
            return media.contentHash();
        }
        Path file = resolveMediaPath(media.path());
        if (file == null || !Files.exists(file)) return null;
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
            if (ensureHash(media) != null) updated++;
        }
        if (updated > 0) log.info("已为 {} 个历史媒体补充 hash", updated);
    }

    public void deleteMediaFile(String urlPath) {
        Path target = resolveMediaPath(urlPath);
        if (target != null) deleteQuietly(target);
    }

    private Path resolveMediaPath(String urlPath) {
        if (urlPath == null || !urlPath.startsWith("/files/")) return null;
        String relative = urlPath.substring("/files/".length());
        Path target = cfg.mediaDir().resolve(relative).normalize();
        if (!target.startsWith(cfg.mediaDir().toAbsolutePath().normalize())) {
            return null;
        }
        return target;
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
                media.postId(),
                media.liveVideoPath(),
                media.originalFormat(),
                media.thumbFailed()
        );
    }

    private String sha256(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
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
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : "";
    }

    private String normalizeFormat(String ext) {
        if (ext == null || ext.isBlank()) return "unknown";
        return "jpg".equals(ext) ? "jpeg" : ext;
    }

    private record LiveMeta(
            boolean microVideo,
            boolean motionPhoto,
            Long microVideoOffset,
            Long motionVideoLength
    ) {
    }
}
