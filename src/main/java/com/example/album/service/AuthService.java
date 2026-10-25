package com.example.album.service;

import com.example.album.config.AppConfig;
import com.example.album.dto.AuthResponse;
import com.example.album.dto.LoginRequest;
import com.example.album.dto.RegisterRequest;
import com.example.album.dto.UserDto;
import com.example.album.exception.ApiException;
import com.example.album.model.User;
import com.example.album.repository.SessionRepository;
import com.example.album.repository.UserRepository;
import com.example.album.security.CurrentUser;
import com.example.album.util.TimeUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final SessionRepository sessions;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AppConfig cfg;

    public AuthService(UserRepository users,
                       SessionRepository sessions,
                       BCryptPasswordEncoder passwordEncoder,
                       AppConfig cfg) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.cfg = cfg;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求参数不正确");
        }
        String userId = request.userId() == null ? "" : request.userId().trim();
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        String password = request.password() == null ? "" : request.password();

        if (!USER_ID_PATTERN.matcher(userId).matches()) {
            throw new ApiException(400, "用户 ID 需为 3~20 位字母、数字或下划线");
        }
        if (nickname.isEmpty() || nickname.length() > 20) {
            throw new ApiException(400, "昵称不能为空且不超过 20 个字符");
        }
        if (password.length() < 4) {
            throw new ApiException(400, "密码至少 4 位");
        }
        if (users.existsByUserId(userId)) {
            throw new ApiException(409, "用户 ID 已被占用");
        }

        long id;
        try {
            id = users.insert(userId, nickname, passwordEncoder.encode(password));
        } catch (DataIntegrityViolationException e) {
            // 并发注册同名用户时由数据库唯一约束兜底。
            throw new ApiException(409, "用户 ID 已被占用");
        }
        String token = issueToken(id);
        return new AuthResponse(token, new UserDto(id, userId, nickname));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String userId = request == null || request.userId() == null
                ? "" : request.userId().trim();
        String password = request == null || request.password() == null
                ? "" : request.password();

        // 无论用户不存在还是密码错误，都返回同一句提示，避免枚举用户。
        User user = users.findByUserId(userId).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(401, "用户 ID 或密码错误");
        }
        String token = issueToken(user.id());
        return new AuthResponse(token,
                new UserDto(user.id(), user.userId(), user.nickname()));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.delete(token);
        }
    }

    public UserDto me(CurrentUser currentUser) {
        return new UserDto(currentUser.id(), currentUser.userId(), currentUser.nickname());
    }

    /** 32 字节随机 token，Base64 URL-safe 且去掉 padding。 */
    private String issueToken(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expiresAt = LocalDateTime.now(TimeUtil.ZONE)
                .plusDays(cfg.tokenDaysOrDefault());
        sessions.deleteExpired();
        sessions.create(token, userId, TimeUtil.format(expiresAt));
        return token;
    }
}
