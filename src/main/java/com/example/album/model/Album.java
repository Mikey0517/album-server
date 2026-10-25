package com.example.album.model;

public record Album(
        long id,
        String title,
        Long coverMediaId,
        String coverThumb,
        int mediaCount,
        String createdAt
) {
}
