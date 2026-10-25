package com.example.album.model;

public record Post(
        long id,
        long authorId,
        String authorNickname,
        String content,
        String postedAt,
        String deletedAt
) {
}
