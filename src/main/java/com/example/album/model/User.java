package com.example.album.model;

public record User(
        long id,
        String userId,
        String nickname,
        String passwordHash,
        String createdAt
) {
}
