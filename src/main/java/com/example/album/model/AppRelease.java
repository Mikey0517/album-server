package com.example.album.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个已发布的 APK。公开 API、后台 history、metadata.json 都使用下面的驼峰字段。
 * 必须显式写 @JsonProperty：项目全局 Jackson 配置是 SNAKE_CASE，
 * 而 /api/app/* 的契约要求 versionCode/versionName/fileSize 等驼峰字段。
 */
public record AppRelease(
        @JsonProperty("versionCode") int versionCode,
        @JsonProperty("versionName") String versionName,
        @JsonProperty("filename") String filename,
        @JsonProperty("fileSize") long fileSize,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("releaseNotes") String releaseNotes,
        @JsonProperty("publishedAt") String publishedAt
) {
}
