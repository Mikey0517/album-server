package com.example.album.model;

public record Album(
        long id,
        String title,
        Long coverMediaId,
        String coverThumb,
        int mediaCount,
        String createdAt,
        Long ownerId,
        String ownerNickname,
        String visibility,
        int shareCount
) {
    public boolean isPrivate() {
        return "private".equals(visibility);
    }
}
