package com.example.album.controller;

import com.example.album.dto.AuthResponse;
import com.example.album.dto.LoginRequest;
import com.example.album.dto.RegisterRequest;
import com.example.album.dto.UserDto;
import com.example.album.security.Auth;
import com.example.album.security.CurrentUser;
import com.example.album.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册并直接签发 30 天 token。 */
    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** 登录失败统一返回「用户 ID 或密码错误」。 */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** 删除当前 token 对应的会话。 */
    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(extractToken(authorization));
        return Map.of("ok", true);
    }

    /** 启动时校验 token 是否仍有效，并返回最新用户信息。 */
    @GetMapping("/me")
    public UserDto me(@Auth CurrentUser user) {
        return authService.me(user);
    }

    private String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
