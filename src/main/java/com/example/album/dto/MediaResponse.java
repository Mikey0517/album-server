package com.example.album.dto;

public record MediaResponse(
        long id,
        String type,
        String thumbUrl,
        String url,
        Integer width,
        Integer height,
        Long durationMs,
        String takenAt,
        String createdAt,
        Long postId
) {
}
