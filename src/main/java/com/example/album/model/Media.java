package com.example.album.model;

public record Media(
        long id,
        Long postId,
        String type,
        String path,
        String thumbPath,
        Integer width,
        Integer height,
        Long durationMs,
        String takenAt,
        int sortOrder,
        String createdAt,
        String contentHash,
        String liveVideoPath,
        String originalFormat,
        boolean thumbFailed,
        boolean favorite
) {
}
