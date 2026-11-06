package com.example.album.dto;

/** 分享相册：userId 是对方的登录用户 ID；role 为 viewer/editor/admin，默认 viewer。 */
public record ShareRequest(String userId, String role) {
}
