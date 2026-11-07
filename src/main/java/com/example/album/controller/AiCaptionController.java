package com.example.album.controller;

import com.example.album.dto.SuggestCaptionRequest;
import com.example.album.service.AiCaptionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiCaptionController {

    private final AiCaptionService aiCaptionService;

    public AiCaptionController(AiCaptionService aiCaptionService) {
        this.aiCaptionService = aiCaptionService;
    }

    /** 根据 1~3 张图片生成朋友圈文案建议（不自动发布）。 */
    @PostMapping("/suggest-caption")
    public Map<String, String> suggest(@RequestBody(required = false) SuggestCaptionRequest request) {
        String caption = aiCaptionService.generate(
                request == null ? null : request.images(),
                request == null ? null : request.context());
        return Map.of("caption", caption, "source", "deepseek");
    }
}
