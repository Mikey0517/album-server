package com.example.album.controller;

import com.example.album.dto.PageResponse;
import com.example.album.dto.PostResponse;
import com.example.album.security.Auth;
import com.example.album.security.CurrentUser;
import com.example.album.service.PostService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /** 朋友圈时间线，posted_at DESC，游标分页。 */
    @GetMapping
    public PageResponse<PostResponse> list(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        return postService.page(cursor, limit);
    }

    /** multipart 发布：content 可选，files 可选，但两者不能同时为空。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(
            @Auth CurrentUser user,
            @RequestParam(value = "content", required = false) String content,
            @RequestPart(value = "files", required = false) MultipartFile[] files) throws IOException {
        long id = postService.create(user.id(), content, files);
        return Map.of("id", id);
    }

    /** 那年今日：往年同月同日的动态。 */
    @GetMapping("/on-this-day")
    public java.util.Map<String, Object> onThisDay() {
        return postService.onThisDay();
    }

    /** 单条动态详情。 */
    @GetMapping("/{id}")
    public PostResponse get(@PathVariable long id) {
        return postService.get(id);
    }

    /** 仅作者本人可删除，软删除。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, @Auth CurrentUser user) {
        postService.delete(id, user.id());
        return Map.of("ok", true);
    }
}
