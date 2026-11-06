package com.example.album.service;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 大图预览：把 HEIC/HEIF/AVIF 以及超大 JPEG 转成 2560px 以内的 JPEG 再返回。
 *
 * App 端直接加载原图有两个问题：Flutter 解不了 HEIC；50MP 的 JPEG 解码容易失败，
 * 于是只能退回缩略图。这里按需生成并缓存到 `media/display/`，原图保持不动。
 */
@Service
public class DisplayImageService {

    private static final Logger log = LoggerFactory.getLogger(DisplayImageService.class);

    private static final int MAX_EDGE = 2560;
    private static final Set<String> CONVERT = Set.of("jpg", "jpeg", "heic", "heif", "avif");
    private static final Set<String> NATIVE = Set.of("png", "webp", "gif", "bmp");

    private final AppConfig cfg;
    private final Path displayDir;

    public DisplayImageService(AppConfig cfg) throws IOException {
        this.cfg = cfg;
        this.displayDir = cfg.mediaDir().resolve("display");
        Files.createDirectories(displayDir);
    }

    public record DisplayFile(Path path, MediaType contentType) {
    }

    public DisplayFile resolve(String name) {
        validate(name);
        Path mediaRoot = cfg.mediaDir().toAbsolutePath().normalize();
        Path source = mediaRoot.resolve(name).normalize();
        if (!source.startsWith(mediaRoot) || !Files.isRegularFile(source)) {
            throw new ApiException(404, "文件不存在");
        }
        String ext = extensionOf(name);
        if (NATIVE.contains(ext)) {
            return new DisplayFile(source, mediaTypeOf(ext));
        }
        Path target = displayDir.resolve(name + ".jpg");
        if (isUsable(target)) {
            return new DisplayFile(target, MediaType.IMAGE_JPEG);
        }
        // 同一张图并发请求时只生成一次。
        synchronized (("display:" + name).intern()) {
            if (isUsable(target)) {
                return new DisplayFile(target, MediaType.IMAGE_JPEG);
            }
            try {
                generate(source, target, ext);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("生成预览图失败: {}", name, e);
                throw new ApiException(500, "图片处理失败");
            }
        }
        if (!isUsable(target)) {
            throw new ApiException(500, "图片处理失败");
        }
        return new DisplayFile(target, MediaType.IMAGE_JPEG);
    }

    private void generate(Path source, Path target, String ext) throws Exception {
        Path input = source;
        Path heicJpeg = null;
        Path temp = displayDir.resolve(UUID.randomUUID() + ".tmp.jpg");
        try {
            if ("heic".equals(ext) || "heif".equals(ext) || "avif".equals(ext)) {
                heicJpeg = displayDir.resolve(UUID.randomUUID() + ".src.jpg");
                run(List.of("heif-convert", source.toString(), heicJpeg.toString()));
                if (!isUsable(heicJpeg)) {
                    throw new ApiException(500, "无法解析该图片");
                }
                input = heicJpeg;
            }
            int[] size = probeSize(input);
            List<String> cmd = new java.util.ArrayList<>(List.of(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", input.toString()));
            if (size != null && Math.max(size[0], size[1]) > MAX_EDGE) {
                int[] scaled = scaleTo(size[0], size[1]);
                cmd.add("-vf");
                cmd.add("scale=" + scaled[0] + ":" + scaled[1]);
            }
            cmd.add("-q:v");
            cmd.add("3");
            cmd.add(temp.toString());
            run(cmd);
            if (!isUsable(temp)) {
                throw new ApiException(500, "图片处理失败");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(heicJpeg);
            deleteQuietly(temp);
        }
    }

    /** 用 ffprobe 读宽高；失败时返回 null（此时按原尺寸重新编码）。 */
    private int[] probeSize(Path file) {
        try {
            String out = run(List.of("ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=p=0", file.toString()));
            String[] parts = out.trim().split(",");
            if (parts.length >= 2) {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                if (w > 0 && h > 0) return new int[]{w, h};
            }
        } catch (Exception e) {
            log.debug("读取图片尺寸失败: {}", file, e);
        }
        return null;
    }

    private int[] scaleTo(int width, int height) {
        double factor = MAX_EDGE / (double) Math.max(width, height);
        int w = Math.max(2, (int) Math.round(width * factor) & ~1);
        int h = Math.max(2, (int) Math.round(height * factor) & ~1);
        return new int[]{w, h};
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("处理超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " 执行失败: " + output);
        }
        return output;
    }

    private void validate(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.contains("..")) {
            throw new ApiException(400, "文件名不合法");
        }
    }

    private boolean isUsable(Path file) {
        try {
            return file != null && Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 忽略清理失败。
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private MediaType mediaTypeOf(String ext) {
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
