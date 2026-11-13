package com.example.album.controller;

import com.example.album.config.AppConfig;
import com.example.album.dto.BatchDeleteReleasesRequest;
import com.example.album.exception.ApiException;
import com.example.album.service.AppUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布包批量管理（X-Upload-Secret，与 AppUpdateController 同一套密钥）。
 *
 * 单条上传/回滚/删除仍在 AppUpdateController；这里只放需要一次处理多个版本的接口。
 */
@RestController
@RequestMapping("/api/admin/releases")
public class AdminReleaseController {

    private static final Logger log = LoggerFactory.getLogger(AdminReleaseController.class);
    private static final String SECRET_HEADER = "X-Upload-Secret";

    private final AppUpdateService updateService;
    private final AppConfig cfg;

    public AdminReleaseController(AppUpdateService updateService, AppConfig cfg) {
        this.updateService = updateService;
        this.cfg = cfg;
    }

    /**
     * 批量删除版本：逐条删除（记录 + 文件），单条失败进 failed，不影响同批其他版本。
     * 当前生效版本会被 deleteRelease 拒绝；空数组返回 400。
     */
    @PostMapping(value = "/batch-delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> batchDelete(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) BatchDeleteReleasesRequest request) {
        requireSecret(secret);
        List<Integer> codes = request == null || request.versionCodes() == null
                ? List.of() : request.versionCodes();
        if (codes.isEmpty()) {
            throw new ApiException(400, "请选择要删除的版本");
        }
        List<Integer> deleted = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Integer code : codes) {
            if (code == null || code <= 0) {
                failed.add(failure(code, "versionCode 非法"));
                continue;
            }
            try {
                updateService.deleteRelease(code);
                deleted.add(code);
            } catch (ApiException e) {
                failed.add(failure(code, e.getMessage()));
            }
        }
        log.info("批量删除发布版本：请求 {} 个，成功 {} 个，失败 {} 个",
                codes.size(), deleted.size(), failed.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result;
    }

    private Map<String, Object> failure(Integer code, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("version_code", code);
        item.put("reason", reason);
        return item;
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
}
