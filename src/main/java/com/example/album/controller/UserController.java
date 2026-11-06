package com.example.album.controller;

import com.example.album.security.Auth;
import com.example.album.security.CurrentUser;
import com.example.album.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 按登录 ID 或昵称搜索用户（用于分享私人相册）。 */
    @GetMapping("/search")
    public Map<String, Object> search(@Auth CurrentUser user,
                                      @RequestParam(value = "q", required = false) String q) {
        return Map.of("items", userService.search(user, q));
    }
}
