package com.example.album.service;

import com.example.album.dto.SuggestCaptionRequest.ImageInput;
import com.example.album.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 朋友圈 AI 帮写：把 1~3 张图片交给 DeepSeek 视觉模型生成文案。
 * API Key 只存在服务端，App 通过 /api/ai/suggest-caption 代理调用。
 */
@Service
public class AiCaptionService {

    private static final Logger log = LoggerFactory.getLogger(AiCaptionService.class);
    private static final int MAX_IMAGES = 3;
    /** 单张 base64 的粗略上限（约 2MB 原始字节）。 */
    private static final int MAX_BASE64_LENGTH = 3 * 1024 * 1024;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final RestClient restClient;

    public AiCaptionService(
            @Value("${deepseek.api-key:}") String apiKey,
            @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${deepseek.model:deepseek-flash}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(0, new MappingJackson2HttpMessageConverter());
                })
                .build();
    }

    public String generate(List<ImageInput> images, String context) {
        if (images == null || images.isEmpty()) {
            throw new ApiException(400, "至少需要一张图片");
        }
        if (images.size() > MAX_IMAGES) {
            throw new ApiException(400, "一次最多 3 张图片");
        }
        for (ImageInput image : images) {
            if (image == null || image.base64() == null || image.base64().isBlank()) {
                throw new ApiException(400, "图片内容为空");
            }
            if (image.base64().length() > MAX_BASE64_LENGTH) {
                throw new ApiException(400, "图片太大，请压缩后再试");
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(502, "AI 文案服务未配置");
        }

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", buildPrompt(images.size(), context)));
        for (ImageInput image : images) {
            String mime = image.mime() == null || image.mime().isBlank()
                    ? "image/jpeg" : image.mime();
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:" + mime + ";base64," + image.base64())));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("temperature", 0.85);
        body.put("max_tokens", 300);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new ApiException(502, "AI 未返回内容");
            }
            List<?> choices = (List<?>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ApiException(502, "AI 未返回内容");
            }
            Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            String caption = sanitize(message == null ? null : (String) message.get("content"));
            if (caption.isEmpty()) {
                throw new ApiException(502, "AI 未返回内容");
            }
            return caption;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DeepSeek 调用失败", e);
            throw new ApiException(502, "AI 文案生成失败，请稍后重试");
        }
    }

    private String buildPrompt(int imageCount, String context) {
        String base = """
                你是一个朋友圈文案助手。请根据用户提供的%d张图片，写一段朋友圈文案。

                要求：
                - 长度 15~50 个汉字，不超过 2 句话
                - 语气自然、克制、有生活感，像一个真实的人在分享
                - 不要使用“记录美好生活”“时光飞逝”“岁月静好”这类套话
                - 不要加引号、不要加“文案：”前缀，直接输出文案本身
                - 可以适度使用 1~2 个 emoji，要贴合画面内容（比如风景用 🌿、美食用 🍜、宠物用 🐾），
                  不要堆砌，不要用与内容无关的 emoji，整体保持克制
                - 如果有多张图，用一句话概括共同氛围，不要逐张描述
                """.formatted(imageCount);

        if (context != null && !context.isBlank()) {
            return base + "\n用户已经写了一段开头：「" + context
                    + "」。请在此基础上自然地续写或润色，保持用户原有的语气，不要重复用户已经写过的内容。";
        }
        return base;
    }

    /** 去掉模型常带的外层引号和「文案：」前缀，emoji 等字符保持原样。 */
    static String sanitize(String raw) {
        if (raw == null) return "";
        // 模型经常返回「文案："…"」，先去前缀再剥引号，最后再兜一次前缀。
        String s = collapse(raw);
        s = stripPrefix(s);
        s = stripQuotes(s);
        s = stripPrefix(s);
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s.trim();
    }

    private static String collapse(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static String stripPrefix(String value) {
        return value.replaceFirst("^(文案|朋友圈文案|推荐文案)[：:]\\s*", "").trim();
    }

    private static String stripQuotes(String value) {
        String s = value.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("“") && s.endsWith("”")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
