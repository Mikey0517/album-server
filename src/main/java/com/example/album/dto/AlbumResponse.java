package com.example.album.dto;

public record AlbumResponse(
        long id,
        String title,
        String coverThumb,
        int mediaCount,
        String createdAt,
        String visibility,
        Long ownerId,
        String ownerNickname,
        boolean isOwner,
        int shareCount
) {
}
