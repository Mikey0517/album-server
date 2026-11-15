package com.example.album.dto;

import java.util.List;

public record PostResponse(
        long id,
        long authorId,
        String authorNickname,
        String content,
        String postedAt,
        String editedAt,
        List<MediaResponse> medias
) {
}
