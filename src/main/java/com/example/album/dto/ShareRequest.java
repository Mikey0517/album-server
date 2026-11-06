package com.example.album.dto;

/** 分享相册：targetUserId 是对方的登录用户 ID（不是昵称）。 */
public record ShareRequest(String userId) {
}
