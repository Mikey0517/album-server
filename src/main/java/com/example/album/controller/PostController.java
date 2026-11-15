package com.example.album.controller;

import com.example.album.dto.PageResponse;
import com.example.album.dto.PostResponse;
import com.example.album.dto.UpdatePostRequest;
import com.example.album.security.Auth;
import com.example.album.security.CurrentUser;
import com.example.album.service.PostService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 编辑已发布动态：仅作者本人。文案、媒体（增删 + 顺序）都能改，
     * 但 posted_at 绝不修改，只刷新 edited_at；返回最新的动态。
     */
    @PutMapping("/{id}")
    public PostResponse update(@PathVariable long id,
                               @Auth CurrentUser user,
                               @RequestBody(required = false) UpdatePostRequest request) {
        return postService.update(
                id,
                user.id(),
                request == null ? null : request.content(),
                request == null ? null : request.mediaIds(),
                request == null ? null : request.addMediaIds());
    }

    /**
     * 编辑前先上传新媒体：只落盘 + 写 media(post_id = NULL)，返回带 id 的媒体，
     * 由客户端随后在 PUT 里用 media_ids / add_media_ids 挂到动态上。
     */
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadMedia(
            @Auth CurrentUser user,
            @RequestPart(value = "files", required = false) MultipartFile[] files)
            throws IOException {
        return Map.of("items", postService.uploadUnbound(files));
    }

    /** 放弃编辑时的兜底清理：只删还没挂到任何动态、也不在任何相册里的临时媒体。 */
    @DeleteMapping("/media/{mediaId}")
    public Map<String, Object> deleteUnboundMedia(@PathVariable long mediaId,
                                                  @Auth CurrentUser user) {
        postService.deleteUnboundMedia(mediaId);
        return Map.of("ok", true);
    }

    /** 仅作者本人可删除，软删除。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, @Auth CurrentUser user) {
        postService.delete(id, user.id());
        return Map.of("ok", true);
    }
}
