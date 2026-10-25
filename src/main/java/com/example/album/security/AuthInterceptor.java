package com.example.album.security;

import com.example.album.repository.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 从 Authorization: Bearer <token> 解析会话用户。
 * 过滤范围由 WebMvcRegistrar 指定（/api/**，排除 register/login）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTR = "currentUser";
    private final SessionRepository sessions;

    public AuthInterceptor(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检请求直接放行，交给 Spring MVC 的 CORS 处理。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, 401, "未登录");
            return false;
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            writeError(response, 401, "未登录");
            return false;
        }
        Optional<CurrentUser> user = sessions.findCurrentUser(token);
        if (user.isEmpty()) {
            writeError(response, 401, "登录已过期");
            return false;
        }
        request.setAttribute(CURRENT_USER_ATTR, user.get());
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String detail) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"detail\":\"" + detail + "\"}");
    }
}
