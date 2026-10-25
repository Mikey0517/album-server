package com.example.album.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 应用级配置，对应 application.yml 的 album.* 节点。
 * 使用 record 保持不可变，避免隐式 setter。
 */
@ConfigurationProperties(prefix = "album")
public record AppConfig(
        String dataDir,
        long maxUploadSize,
        int tokenDays,
        String allowedOrigins,
        String uploadSecret
) {

    public Path dataDirPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    /** 原始媒体目录：<DATA_DIR>/media */
    public Path mediaDir() {
        return dataDirPath().resolve("media");
    }

    /** 缩略图目录：<DATA_DIR>/media/thumbs */
    public Path thumbDir() {
        return mediaDir().resolve("thumbs");
    }

    /** App 发布包目录：<DATA_DIR>/releases */
    public Path releasesDir() {
        return dataDirPath().resolve("releases");
    }

    public String uploadSecretOrDefault() {
        return uploadSecret == null || uploadSecret.isBlank()
                ? "change-me-in-production" : uploadSecret;
    }

    public int tokenDaysOrDefault() {
        return tokenDays > 0 ? tokenDays : 30;
    }
}
