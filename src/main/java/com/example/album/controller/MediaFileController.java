package com.example.album.controller;

import com.example.album.service.DisplayImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** 大图预览：/files/display/<文件名>，按需生成并缓存 2560px 的 JPEG。 */
@RestController
public class MediaFileController {

    private final DisplayImageService displayImageService;

    public MediaFileController(DisplayImageService displayImageService) {
        this.displayImageService = displayImageService;
    }

    @GetMapping("/files/display/{name:.+}")
    public ResponseEntity<Resource> display(@PathVariable String name) {
        DisplayImageService.DisplayFile file = displayImageService.resolve(name);
        return ResponseEntity.ok()
                .contentType(file.contentType())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(new FileSystemResource(file.path()));
    }
}
