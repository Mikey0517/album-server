package com.example.album.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * /files/** 静态文件映射与 CORS。鉴权拦截器的注册放在 WebMvcRegistrar。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppConfig cfg;

    public WebConfig(AppConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path mediaDir = cfg.mediaDir().toAbsolutePath();
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + mediaDir + "/")
                .setCachePeriod(3600);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = cfg.allowedOrigins() == null ? "*" : cfg.allowedOrigins().trim();
        var mapping = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .maxAge(3600);
        if (origins.equals("*") || origins.isBlank()) {
            mapping.allowedOriginPatterns("*");
        } else {
            mapping.allowedOrigins(java.util.Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new));
        }
    }
}
