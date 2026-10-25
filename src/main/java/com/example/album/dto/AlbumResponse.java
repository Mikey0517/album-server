package com.example.album.dto;

public record AlbumResponse(
        long id,
        String title,
        String coverThumb,
        int mediaCount,
        String createdAt
) {
}
