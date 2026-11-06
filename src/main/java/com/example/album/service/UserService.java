package com.example.album.service;

import com.example.album.dto.UserDto;
import com.example.album.exception.ApiException;
import com.example.album.repository.UserRepository;
import com.example.album.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private static final int MAX_NICKNAME_LENGTH = 20;
    private static final int SEARCH_LIMIT = 10;

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** 分享相册时按登录 ID 或昵称找人；不返回自己，最多 10 条。 */
    public List<UserDto> search(CurrentUser current, String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) return List.of();
        if (keyword.length() > MAX_NICKNAME_LENGTH) {
            keyword = keyword.substring(0, MAX_NICKNAME_LENGTH);
        }
        return users.search(keyword, current.id(), SEARCH_LIMIT).stream()
                .map(user -> new UserDto(user.id(), user.userId(), user.nickname()))
                .toList();
    }

    /** 修改昵称：非空、1~20 字、忽略大小写全局唯一。 */
    @Transactional
    public UserDto updateNickname(CurrentUser current, String nickname) {
        String normalized = nickname == null ? "" : nickname.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NICKNAME_LENGTH) {
            throw new ApiException(400, "昵称不能为空且不超过 " + MAX_NICKNAME_LENGTH + " 个字符");
        }
        if (users.existsByNickname(normalized, current.id())) {
            throw new ApiException(409, "昵称已被使用");
        }
        try {
            users.updateNickname(current.id(), normalized);
        } catch (DataIntegrityViolationException e) {
            // 并发改名时由唯一索引兜底。
            throw new ApiException(409, "昵称已被使用");
        }
        return new UserDto(current.id(), current.userId(), normalized);
    }
}
