package com.example.album.controller;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import com.example.album.model.PatchRecord;
import com.example.album.service.PatchService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dart 热更新补丁接口。
 *
 * 公开（App 直接调用，无鉴权）：/api/patch/latest、/api/patch/download/{filename}。
 * 管理（X-Upload-Secret，与 AppUpdateController 同一套密钥）：/api/admin/patch/**。
 *
 * latest 对 App 必须永远 200：参数缺失或非法时返回 {"has_update":false}，
 * 不能抛 4xx 让客户端弹错误。
 */
@RestController
@RequestMapping("/api")
public class PatchController {

    private static final MediaType ZIP_MEDIA_TYPE = MediaType.parseMediaType("application/zip");
    private static final String SECRET_HEADER = "X-Upload-Secret";
    private static final String DOWNLOAD_PREFIX = "/api/patch/download/";

    private final PatchService patchService;
    private final AppConfig cfg;

    public PatchController(PatchService patchService, AppConfig cfg) {
        this.patchService = patchService;
        this.cfg = cfg;
    }

    /** 公开：基线版本对应的最新可用补丁。参数非法一律 has_update=false。 */
    @GetMapping("/patch/latest")
    public Map<String, Object> latest(
            @RequestParam(value = "base_version_code", required = false) String baseVersionCode,
            @RequestParam(value = "current_patch_version", required = false) String currentPatchVersion) {
        Integer base = parseInt(baseVersionCode);
        if (base == null || base <= 0) {
            return Map.of("has_update", false);
        }
        Integer current = parseInt(currentPatchVersion);
        Optional<PatchRecord> found = patchService.latest(base, current == null ? 0 : Math.max(current, 0));
        if (found.isEmpty()) {
            return Map.of("has_update", false);
        }
        PatchRecord patch = found.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("has_update", true);
        result.put("patch_version", patch.patchVersion());
        result.put("download_url", DOWNLOAD_PREFIX + patch.filename());
        result.put("file_size", patch.fileSize());
        result.put("sha256", patch.sha256());
        result.put("release_notes", patch.releaseNotes() == null ? "" : patch.releaseNotes());
        return result;
    }

    /** 公开：补丁下载，支持单段 Range。只允许 patches 目录里的 .zip。 */
    @GetMapping("/patch/download/{filename:.+}")
    public void download(@PathVariable String filename,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false)
                         String rangeHeader,
                         HttpServletResponse response) throws IOException {
        Path file = patchService.fileFor(filename);
        serveFile(file, filename, ZIP_MEDIA_TYPE, rangeHeader, response);
    }

    /** 管理：上传补丁。补丁号由服务端自增，响应里返回。 */
    @PostMapping(value = "/admin/patch/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestPart("file") MultipartFile file,
            @RequestParam("base_version_code") int baseVersionCode,
            @RequestParam(value = "release_notes", required = false) String releaseNotes) {
        requireSecret(secret);
        PatchRecord patch = patchService.save(file, baseVersionCode, releaseNotes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("patch_version", patch.patchVersion());
        result.put("filename", patch.filename());
        return result;
    }

    /** 管理：补丁列表。base_version_code 可选，缺省返回全部。 */
    @GetMapping("/admin/patch/list")
    public Map<String, Object> list(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestParam(value = "base_version_code", required = false) Integer baseVersionCode) {
        requireSecret(secret);
        List<Map<String, Object>> patches = patchService.list(baseVersionCode).stream()
                .map(this::toMap)
                .toList();
        return Map.of("patches", patches);
    }

    /** 管理：下线补丁，之后 latest 不再下发。 */
    @PostMapping("/admin/patch/{id}/deactivate")
    public Map<String, Object> deactivate(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @PathVariable long id) {
        requireSecret(secret);
        patchService.deactivate(id);
        return Map.of("ok", true);
    }

    /** 管理：删除补丁（数据库记录 + 磁盘文件）。 */
    @DeleteMapping("/admin/patch/{id}")
    public Map<String, Object> delete(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @PathVariable long id) {
        requireSecret(secret);
        patchService.delete(id);
        return Map.of("ok", true);
    }

    private Map<String, Object> toMap(PatchRecord patch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", patch.id());
        item.put("base_version_code", patch.baseVersionCode());
        item.put("patch_version", patch.patchVersion());
        item.put("filename", patch.filename());
        item.put("file_size", patch.fileSize());
        item.put("sha256", patch.sha256());
        item.put("release_notes", patch.releaseNotes() == null ? "" : patch.releaseNotes());
        item.put("is_active", patch.isActive());
        item.put("created_at", patch.createdAt());
        return item;
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 与 AppUpdateController 相同的密钥校验：常量时间比较，失败 401。 */
    private void requireSecret(String provided) {
        if (provided == null) {
            throw new ApiException(401, "密钥错误");
        }
        byte[] expected = cfg.uploadSecretOrDefault().getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(401, "密钥错误");
        }
    }

    /** 把本地文件按单段 Range 发出去；与 AppUpdateController 的实现保持一致。 */
    private void serveFile(Path file, String filename, MediaType mediaType,
                           String rangeHeader, HttpServletResponse response) throws IOException {
        long size = Files.size(file);
        long start = 0;
        long end = size - 1;

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        response.setContentType(mediaType.toString());

        if (rangeHeader != null && !rangeHeader.isBlank()) {
            Range range = parseRange(rangeHeader, size);
            if (range == null) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLength(0);
                return;
            }
            start = range.start();
            end = range.end();
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + size);
        } else {
            response.setStatus(HttpStatus.OK.value());
        }

        long length = end - start + 1;
        response.setContentLengthLong(length);
        try (RandomAccessFile patch = new RandomAccessFile(file.toFile(), "r")) {
            patch.seek(start);
            ServletOutputStream out = response.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = patch.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * 解析单段 Range。格式：bytes=start-end / bytes=start- / bytes=-suffix。
     * 多段 Range 本项目不使用，直接返回 null（416）。
     */
    private Range parseRange(String header, long size) {
        if (!header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startPart.isEmpty()) {
                if (endPart.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
            }
            if (start < 0 || start >= size || end < start) {
                return null;
            }
            end = Math.min(end, size - 1);
            return new Range(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Range(long start, long end) {
    }
}
