package com.example.album.controller;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import com.example.album.model.AppRelease;
import com.example.album.model.ReleaseManifest;
import com.example.album.service.AppUpdateService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/app")
public class AppUpdateController {

    private static final MediaType APK_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.android.package-archive");
    private static final String SECRET_HEADER = "X-Upload-Secret";

    private final AppUpdateService updateService;
    private final AppConfig cfg;

    public AppUpdateController(AppUpdateService updateService, AppConfig cfg) {
        this.updateService = updateService;
        this.cfg = cfg;
    }

    /** 公开：当前生效版本。无版本时 404。 */
    @GetMapping("/latest")
    public AppRelease latest() {
        return updateService.getLatest()
                .orElseThrow(() -> new ApiException(404, "暂无发布版本"));
    }

    /**
     * 公开：APK 下载。支持单段 Range（断点续传/重试）。
     * 只允许下载 manifest 中登记过的文件名，避免任意文件读取。
     */
    @GetMapping("/download/{filename:.+}")
    public void download(@PathVariable String filename,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false)
                         String rangeHeader,
                         HttpServletResponse response) throws IOException {
        Path file = updateService.fileFor(filename);
        long size = Files.size(file);
        long start = 0;
        long end = size - 1;

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");
        response.setContentType(APK_MEDIA_TYPE.toString());

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
        try (RandomAccessFile apk = new RandomAccessFile(file.toFile(), "r")) {
            apk.seek(start);
            ServletOutputStream out = response.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = apk.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /** 管理：上传新版本。该接口会自动把新版本设置为当前版本。 */
    @PostMapping(value = "/release", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> release(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestPart("file") MultipartFile file,
            @RequestParam("versionCode") int versionCode,
            @RequestParam("versionName") String versionName,
            @RequestParam(value = "notes", required = false, defaultValue = "") String notes) {
        requireSecret(secret);
        AppRelease release = updateService.saveRelease(
                file, versionCode, versionName, notes);
        return Map.of(
                "ok", true,
                "version_code", release.versionCode(),
                "version_name", release.versionName()
        );
    }

    /** 管理：全部版本（降序）及当前生效版本。 */
    @GetMapping("/history")
    public Map<String, Object> history(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        requireSecret(secret);
        ReleaseManifest manifest = updateService.loadManifest();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current_version_code", manifest.currentVersionCode());
        result.put("releases", manifest.releases());
        return result;
    }

    /** 管理：软回滚。只改当前指针，不强制已安装的高版本降级。 */
    @PostMapping("/rollback/{versionCode}")
    public Map<String, Object> rollback(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @PathVariable int versionCode) {
        requireSecret(secret);
        updateService.rollback(versionCode);
        return Map.of("ok", true, "current_version_code", versionCode);
    }

    /** 管理：删除历史版本（不能删当前版本）。 */
    @DeleteMapping("/{versionCode}")
    public Map<String, Object> delete(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @PathVariable int versionCode) {
        requireSecret(secret);
        updateService.deleteRelease(versionCode);
        return Map.of("ok", true);
    }

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
                // 末尾 N 字节
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
        long length() {
            return end - start + 1;
        }
    }
}
