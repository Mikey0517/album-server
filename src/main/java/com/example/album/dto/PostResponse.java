package com.example.album.dto;

import java.util.List;

public record PostResponse(
        long id,
        long authorId,
        String authorNickname,
        String content,
        String postedAt,
        List<MediaResponse> medias
) {
}
