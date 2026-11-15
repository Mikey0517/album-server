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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                post.editedAt(),
                mediaMap.getOrDefault(post.id(), List.of())
        )).toList();

        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Post last = rows.get(rows.size() - 1);
            nextCursor = CursorUtil.build(last.postedAt(), last.id());
        }
        return new PageResponse<>(items, nextCursor);
    }

    /** 那年今日：往年同月同日发布的动态。 */
    public java.util.Map<String, Object> onThisDay() {
        LocalDate today = LocalDate.now();
        String monthDay = String.format("%02d-%02d",
                today.getMonthValue(), today.getDayOfMonth());
        List<Post> rows = posts.onThisDay(monthDay, today.getYear());
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
                post.editedAt(),
                mediaMap.getOrDefault(post.id(), List.of())
        )).toList();
        return Map.of("date", monthDay, "items", items);
    }

    public PostResponse get(long id) {
        Post post = posts.findById(id)
                .orElseThrow(() -> new ApiException(404, "动态不存在"));
        List<MediaResponse> mediaResponses = medias.findByPostId(id).stream()
                .map(mediaService::toResponse)
                .toList();
        return new PostResponse(post.id(), post.authorId(), post.authorNickname(),
                post.content(), post.postedAt(), post.editedAt(), mediaResponses);
    }

    /** 只允许作者删除，软删除后朋友圈相册通过 JOIN 自动不可见。 */
    @Transactional
    public void delete(long postId, long currentUserId) {
        Long authorId = posts.findAuthorId(postId);
        if (authorId == null) {
            throw new ApiException(404, "动态不存在");
        }
        if (authorId != currentUserId) {
            throw new ApiException(403, "只能删除自己的动态");
        }
        int affected = posts.softDelete(postId, currentUserId);
        if (affected == 0) return;

        // 动态媒体与相册共享物理文件：删记录，文件在无其他引用时才删除。
        for (Media media : medias.findByPostId(postId)) {
            if (medias.countAlbumLinks(media.id()) > 0) {
                // 仍被普通相册引用：只解除动态关联，相册里的显示保留。
                medias.clearPostId(media.id());
            } else {
                mediaService.deleteFilesAndRecord(media);
            }
        }
    }

    /**
     * 编辑已发布动态：只有作者本人能改；文案和媒体都能改。
     *
     * <p>posted_at 永远不动——改字改图都不改变发布时间，列表里的位置和时间保持原样，
     * 只把 edited_at 刷成现在。
     *
     * <p>媒体语义见 {@link com.example.album.dto.UpdatePostRequest}：
     * media_ids 整组替换 + 排序，add_media_ids 追加到末尾；被移除的只解绑不删文件。
     */
    @Transactional
    public PostResponse update(long postId, long currentUserId, String content,
                               List<Long> mediaIds, List<Long> addMediaIds) {
        Post post = posts.findById(postId)
                .orElseThrow(() -> new ApiException(404, "动态不存在"));
        if (post.authorId() != currentUserId) {
            throw new ApiException(403, "只能编辑自己的动态");
        }

        // content 没传表示「只改媒体，文案不动」；传空串表示「清空文案」。
        String text;
        if (content == null) {
            text = post.content();
        } else {
            text = content.trim();
            if (text.isEmpty()) {
                text = null;
            }
        }
        if (text != null && text.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(400, "文字内容过长");
        }

        List<Media> current = medias.findByPostId(postId);

        // 1. 保留/排序后的媒体集合：没传 media_ids 就沿用现在的顺序。
        List<Media> finalMedias = new ArrayList<>();
        if (mediaIds != null) {
            finalMedias.addAll(resolveMedia(postId, currentUserId, mediaIds, "media_ids"));
        } else {
            finalMedias.addAll(current);
        }

        // 2. 新上传的媒体按数组顺序追加到末尾。
        if (addMediaIds != null) {
            for (Media media : resolveMedia(postId, currentUserId, addMediaIds, "add_media_ids")) {
                for (Media kept : finalMedias) {
                    if (kept.id() == media.id()) {
                        throw new ApiException(400, "媒体重复: " + media.id());
                    }
                }
                finalMedias.add(media);
            }
        }

        if (finalMedias.size() > MAX_FILES) {
            throw new ApiException(400, "一条动态最多 " + MAX_FILES + " 个媒体");
        }
        // 改完什么都没有的动态不显示任何东西，直接拒绝。
        if (text == null && finalMedias.isEmpty()) {
            throw new ApiException(400, "请输入内容或选择媒体");
        }

        // 3. 被移除的只解绑：文件不删，仍在相册里的照片继续可见。
        Set<Long> keptIds = new HashSet<>();
        for (Media media : finalMedias) {
            keptIds.add(media.id());
        }
        for (Media media : current) {
            if (!keptIds.contains(media.id())) {
                medias.clearPostId(media.id());
            }
        }

        // 4. 落库顺序就是提交上来的数组顺序。
        int order = 0;
        for (Media media : finalMedias) {
            medias.bindToPost(media.id(), postId, order++);
        }

        posts.updateContent(postId, currentUserId, text, TimeUtil.now());

        Post updated = posts.findById(postId)
                .orElseThrow(() -> new ApiException(404, "动态不存在"));
        List<MediaResponse> mediaResponses = medias.findByPostId(postId).stream()
                .map(mediaService::toResponse)
                .toList();
        return new PostResponse(updated.id(), updated.authorId(), updated.authorNickname(),
                updated.content(), updated.postedAt(), updated.editedAt(), mediaResponses);
    }

    /**
     * 校验一组媒体 id：必须存在，且只能是「还没挂动态的新上传」或「本来就在这条动态上」的。
     * 挂到别人动态上的媒体一律 400——不能把别人的照片挪进自己的动态。
     */
    private List<Media> resolveMedia(long postId, long currentUserId,
                                     List<Long> ids, String field) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new ApiException(400, field + " 里有非法媒体 id");
            }
            if (!unique.add(id)) {
                throw new ApiException(400, field + " 里有重复媒体 id: " + id);
            }
        }
        List<Media> found = medias.findByIds(List.copyOf(unique));
        if (found.size() != unique.size()) {
            Set<Long> missing = new LinkedHashSet<>(unique);
            for (Media media : found) {
                missing.remove(media.id());
            }
            throw new ApiException(400, "媒体不存在: " + missing);
        }
        Map<Long, Media> byId = new HashMap<>();
        for (Media media : found) {
            byId.put(media.id(), media);
        }
        List<Media> ordered = new ArrayList<>(unique.size());
        for (Long id : unique) {
            Media media = byId.get(id);
            Long ownerPost = media.postId();
            if (ownerPost != null && ownerPost != postId) {
                Long owner = posts.findAuthorId(ownerPost);
                if (owner == null) {
                    throw new ApiException(400, "媒体不可用: " + id);
                }
                if (owner != currentUserId) {
                    throw new ApiException(400, "媒体不属于你: " + id);
                }
                throw new ApiException(400, "媒体已属于其他动态: " + id);
            }
            ordered.add(media);
        }
        return ordered;
    }

    /**
     * 编辑动态前先上传、拿到 id 再随 PUT 提交：这里只落盘写 media(post_id = NULL)，
     * 不挂到任何动态上，所以用户中途放弃也不会留下「半条动态」。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MediaResponse> uploadUnbound(MultipartFile[] files) throws IOException {
        List<MultipartFile> validFiles = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    validFiles.add(file);
                }
            }
        }
        if (validFiles.isEmpty()) {
            throw new ApiException(400, "请选择要上传的文件");
        }
        if (validFiles.size() > MAX_FILES) {
            throw new ApiException(400, "一次最多上传 " + MAX_FILES + " 个文件");
        }
        List<MediaResponse> items = new ArrayList<>();
        int order = 0;
        for (MultipartFile file : validFiles) {
            Media media = mediaService.store(file, null, order++);
            items.add(mediaService.toResponse(media));
        }
        return items;
    }

    /**
     * 取消编辑时的兜底清理：只删「还没挂到任何动态、也不在任何相册里」的临时媒体。
     * 已经挂上的返回 400——那种删除走删除动态 / 相册移除。
     */
    @Transactional
    public void deleteUnboundMedia(long mediaId) {
        Media media = medias.findById(mediaId)
                .orElseThrow(() -> new ApiException(404, "媒体不存在"));
        if (media.postId() != null) {
            throw new ApiException(400, "媒体已挂到动态上，不能从这里删除");
        }
        if (medias.countAlbumLinks(mediaId) > 0) {
            throw new ApiException(400, "媒体已在相册里，不能从这里删除");
        }
        mediaService.deleteFilesAndRecord(media);
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
