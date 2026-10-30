package com.example.album.controller;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import com.example.album.service.MediaService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/repair")
public class AdminRepairController {

    private static final String SECRET_HEADER = "X-Upload-Secret";
    private final MediaService mediaService;
    private final AppConfig cfg;

    public AdminRepairController(MediaService mediaService, AppConfig cfg) {
        this.mediaService = mediaService;
        this.cfg = cfg;
    }

    /** 分批重算拍摄时间；X-Upload-Secret 鉴权。 */
    @PostMapping("/taken-at")
    public Map<String, Object> repairTakenAt(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestParam(value = "dry_run", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        requireSecret(secret);
        return mediaService.repairTakenAt(dryRun, limit, offset);
    }

    private void requireSecret(String provided) {
        if (provided == null) throw new ApiException(401, "密钥错误");
        byte[] expected = cfg.uploadSecretOrDefault().getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(401, "密钥错误");
        }
    }
}
