package com.example.album.dto;

import java.util.List;

/** AI 文案建议：images 为纯 base64（不含 data URL 前缀），context 是用户已输入的文字。 */
public record SuggestCaptionRequest(List<ImageInput> images, String context) {

    public record ImageInput(String base64, String mime) {
    }
}
