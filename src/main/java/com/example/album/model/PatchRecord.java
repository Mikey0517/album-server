package com.example.album.model;

/**
 * 一条 Dart 热更新补丁记录，对应 patches 表。
 * 补丁强绑定基线 APK：base_version_code 相同的补丁里 patch_version 自增。
 */
public record PatchRecord(
        long id,
        int baseVersionCode,
        int patchVersion,
        String filename,
        long fileSize,
        String sha256,
        String releaseNotes,
        boolean isActive,
        String createdAt
) {
}
