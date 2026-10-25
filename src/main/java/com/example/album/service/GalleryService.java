package com.example.album.service;

import com.example.album.dto.MediaResponse;
import com.example.album.dto.PageResponse;
import com.example.album.model.Media;
import com.example.album.repository.MediaRepository;
import com.example.album.util.CursorUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 朋友圈相册是只读聚合视图，不拥有任何写接口。
 */
@Service
public class GalleryService {

    private final MediaRepository medias;
    private final MediaService mediaService;

    public GalleryService(MediaRepository medias, MediaService mediaService) {
        this.medias = medias;
        this.mediaService = mediaService;
    }

    public PageResponse<MediaResponse> feed(String cursor, Integer limit) {
        int pageSize = clamp(limit, 60, 1, 100);
        CursorUtil.Cursor parsed = CursorUtil.parse(cursor);
        List<Media> rows = medias.feedPage(
                parsed == null ? null : parsed.time(),
                parsed == null ? null : parsed.id(),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, pageSize));
        }
        List<MediaResponse> items = rows.stream().map(mediaService::toResponse).toList();
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Media last = rows.get(rows.size() - 1);
            nextCursor = CursorUtil.build(last.takenAt(), last.id());
        }
        return new PageResponse<>(items, nextCursor);
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
