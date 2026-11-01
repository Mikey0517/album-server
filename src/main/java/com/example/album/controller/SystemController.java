package com.example.album.controller;

import com.example.album.config.AppConfig;
import com.example.album.exception.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AppConfig cfg;

    public SystemController(AppConfig cfg) {
        this.cfg = cfg;
    }

    /** 当前数据盘容量，登录用户可读。 */
    @GetMapping("/storage")
    public Map<String, Object> storage() {
        try {
            Path dataDir = cfg.dataDirPath();
            Files.createDirectories(dataDir);
            FileStore store = Files.getFileStore(dataDir);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long used = Math.max(0, total - free);
            double ratio = total > 0 ? used / (double) total : 0.0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_bytes", total);
            result.put("free_bytes", free);
            result.put("used_bytes", used);
            result.put("used_ratio", ratio);
            return result;
        } catch (Exception e) {
            throw new ApiException(500, "读取磁盘空间失败");
        }
    }
}
