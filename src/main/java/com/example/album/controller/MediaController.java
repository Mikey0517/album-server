package com.example.album.controller;

import com.example.album.exception.ApiException;
import com.example.album.model.Media;
import com.example.album.repository.MediaRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaRepository medias;

    public MediaController(MediaRepository medias) {
        this.medias = medias;
    }

    /** 切换收藏状态（当前为家庭共享收藏，所有登录用户都可操作）。 */
    @PostMapping("/{id}/favorite")
    public Map<String, Object> toggleFavorite(@PathVariable long id) {
        if (medias.findById(id).isEmpty()) {
            throw new ApiException(404, "媒体不存在");
        }
        medias.toggleFavorite(id);
        Media media = medias.findById(id)
                .orElseThrow(() -> new ApiException(404, "媒体不存在"));
        return Map.of("favorite", media.favorite());
    }
}
