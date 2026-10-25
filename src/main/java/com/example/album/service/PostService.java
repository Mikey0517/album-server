package com.example.album.service;

import com.example.album.dto.MediaResponse;
import com.example.album.dto.PageResponse;
import com.example.album.dto.PostResponse;
import com.example.album.exception.ApiException;
import com.example.album.model.Media;
import com.example.album.model.Post;
import com.example.album.repository.MediaRepository;
import com.example.album.repository.PostRepository;
import com.example.album.util.CursorUtil;
import com.example.album.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostService {

    private static final int MAX_CONTENT_LENGTH = 5000;
    private static final int MAX_FILES = 20;

    private final PostRepository posts;
    private final MediaRepository medias;
    private final MediaService mediaService;

    public PostService(PostRepository posts,
                       MediaRepository medias,
                       MediaService mediaService) {
        this.posts = posts;
        this.medias = medias;
        this.mediaService = mediaService;
    }

    /**
     * 发布动态：文字和媒体至少有一项。媒体与帖子写入同一事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public long create(long authorId, String content, MultipartFile[] files) throws IOException {
        String text = content == null ? null : content.trim();
        if (text != null && text.isEmpty()) {
            text = null;
        }
        if (text != null && text.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(400, "文字内容过长");
        }

        List<MultipartFile> validFiles = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    validFiles.add(file);
                }
            }
        }
        if (validFiles.size() > MAX_FILES) {
            throw new ApiException(400, "一次最多上传 " + MAX_FILES + " 个文件");
        }
        if (text == null && validFiles.isEmpty()) {
            throw new ApiException(400, "请输入内容或选择媒体");
        }

        String postedAt = TimeUtil.now();
        long postId = posts.insert(authorId, text, postedAt);
        int order = 0;
        for (MultipartFile file : validFiles) {
            mediaService.store(file, postId, order++);
        }
        return postId;
    }

    public PageResponse<PostResponse> page(String cursor, Integer limit) {
        int pageSize = clamp(limit, 20, 1, 50);
        CursorUtil.Cursor parsed = CursorUtil.parse(cursor);
        List<Post> rows = posts.page(
                parsed == null ? null : parsed.time(),
                parsed == null ? null : parsed.id(),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, pageSize));
        }

        List<Long> postIds = rows.stream().map(Post::id).toList();
        List<Media> allMedia = medias.findByPostIds(postIds);
        Map<Long, List<MediaResponse>> mediaMap = new HashMap<>();
        for (Media media : allMedia) {
            mediaMap.computeIfAbsent(media.postId(), key -> new ArrayList<>())
                    .add(mediaService.toResponse(media));
        }

        List<PostResponse> items = rows.stream().map(post -> new PostResponse(
                post.id(),
                post.authorId(),
                post.authorNickname(),
                post.content(),
                post.postedAt(),
                mediaMap.getOrDefault(post.id(), List.of())
        )).toList();

        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Post last = rows.get(rows.size() - 1);
            nextCursor = CursorUtil.build(last.postedAt(), last.id());
        }
        return new PageResponse<>(items, nextCursor);
    }

    public PostResponse get(long id) {
        Post post = posts.findById(id)
                .orElseThrow(() -> new ApiException(404, "动态不存在"));
        List<MediaResponse> mediaResponses = medias.findByPostId(id).stream()
                .map(mediaService::toResponse)
                .toList();
        return new PostResponse(post.id(), post.authorId(), post.authorNickname(),
                post.content(), post.postedAt(), mediaResponses);
    }

    /** 只允许作者删除，软删除后朋友圈相册通过 JOIN 自动不可见。 */
    public void delete(long postId, long currentUserId) {
        Long authorId = posts.findAuthorId(postId);
        if (authorId == null) {
            throw new ApiException(404, "动态不存在");
        }
        if (authorId != currentUserId) {
            throw new ApiException(403, "只能删除自己的动态");
        }
        posts.softDelete(postId, currentUserId);
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
