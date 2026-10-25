package com.example.album.config;

import com.example.album.security.AuthInterceptor;
import com.example.album.security.AuthResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 @Auth 参数解析器和 token 拦截器。
 */
@Configuration
public class WebMvcRegistrar implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuthResolver authResolver;

    public WebMvcRegistrar(AuthInterceptor authInterceptor, AuthResolver authResolver) {
        this.authInterceptor = authInterceptor;
        this.authResolver = authResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        // App 更新接口使用独立规则：latest/download 公开，
                        // 管理接口在 Controller 内校验 X-Upload-Secret。
                        "/api/app/**",
                        "/error"
                );
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authResolver);
    }
}
