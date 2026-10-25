package com.example.album.security;

/** 当前已登录用户，由 AuthInterceptor 写入 request attribute。 */
public record CurrentUser(long id, String userId, String nickname) {
}
