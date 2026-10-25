package com.example.album.controller;

import com.example.album.dto.MediaResponse;
import com.example.album.dto.PageResponse;
import com.example.album.service.GalleryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gallery")
public class GalleryController {

    private final GalleryService galleryService;

    public GalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    /** 朋友圈相册：所有有效动态的媒体，taken_at DESC，只读。 */
    @GetMapping("/feed")
    public PageResponse<MediaResponse> feed(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "60") Integer limit) {
        return galleryService.feed(cursor, limit);
    }
}
