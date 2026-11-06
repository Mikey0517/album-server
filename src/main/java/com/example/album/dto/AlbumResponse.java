package com.example.album.dto;

/**
 * myRole: family（家庭相册，所有人可编辑）/ owner / viewer / editor / admin。
 */
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
        int shareCount,
        String myRole
) {
}
