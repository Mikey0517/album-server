package com.example.album.service;

import com.example.album.dto.AlbumPatchRequest;
import com.example.album.dto.AlbumResponse;
import com.example.album.dto.AlbumTitleRequest;
import com.example.album.dto.MediaResponse;
import com.example.album.dto.UserDto;
import com.example.album.exception.ApiException;
import com.example.album.model.Album;
import com.example.album.model.Media;
import com.example.album.model.User;
import com.example.album.repository.AlbumRepository;
import com.example.album.repository.MediaRepository;
import com.example.album.repository.UserRepository;
import com.example.album.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlbumService {

    private static final int MAX_FILES = 30;
    private static final int MAX_TITLE_LENGTH = 40;

    private static final String VISIBILITY_FAMILY = "family";
    private static final String VISIBILITY_PRIVATE = "private";

    private final AlbumRepository albums;
    private final MediaRepository medias;
    private final MediaService mediaService;
    private final UserRepository users;

    public AlbumService(AlbumRepository albums,
                        MediaRepository medias,
                        MediaService mediaService,
                        UserRepository users) {
        this.albums = albums;
        this.medias = medias;
        this.mediaService = mediaService;
        this.users = users;
    }

    /** 当前用户可见的相册：家庭公共 + 自己创建的私人 + 别人分享给我的私人。 */
    public List<AlbumResponse> list(CurrentUser user) {
        return albums.findVisibleTo(user.id()).stream()
                .map(album -> toResponse(album, user.id()))
                .toList();
    }

    @Transactional
    public AlbumResponse create(CurrentUser user, AlbumTitleRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求参数不正确");
        }
        String normalized = normalizeTitle(request.title());
        boolean isPrivate = Boolean.TRUE.equals(request.isPrivate());
        String visibility = isPrivate ? VISIBILITY_PRIVATE : VISIBILITY_FAMILY;
        long id = albums.insert(normalized, user.id(), visibility);
        return albums.findById(id).map(album -> toResponse(album, user.id()))
                .orElseThrow(() -> new ApiException(500, "创建相册失败"));
    }

    @Transactional
    public AlbumResponse patch(CurrentUser user, long albumId, AlbumPatchRequest request) {
        requireManage(albumId, user);
        if (request == null) {
            throw new ApiException(400, "请求参数不正确");
        }
        if (request.title() != null) {
            albums.updateTitle(albumId, normalizeTitle(request.title()));
        }
        if (request.coverMediaId() != null) {
            long mediaId = request.coverMediaId();
            // 封面必须真实存在且已属于该相册。
            if (medias.findById(mediaId).isEmpty() || !medias.existsInAlbum(albumId, mediaId)) {
                throw new ApiException(400, "封面媒体不属于该相册");
            }
            albums.updateCover(albumId, mediaId);
        }
        return albums.findById(albumId).map(album -> toResponse(album, user.id()))
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
    }

    /**
     * 删除相册 + 清理孤儿媒体：
     * 只物理删除 post_id 为 NULL 且已不再属于任何相册的媒体；
     * 朋友圈媒体即使被误关联，也保留原记录与文件。
     */
    @Transactional
    public void delete(CurrentUser user, long albumId) {
        requireManage(albumId, user);
        List<Media> candidates = medias.findOrphanCandidatesByAlbum(albumId);
        albums.delete(albumId); // CASCADE 删除 album_media / album_shares
        for (Media media : candidates) {
            if (medias.countAlbumLinks(media.id()) == 0) {
                mediaService.deleteFilesAndRecord(media);
            }
        }
    }

    public Map<String, Object> detail(CurrentUser user, long albumId) {
        Album album = requireView(albumId, user);
        List<MediaResponse> items = medias.findByAlbum(albumId).stream()
                .map(mediaService::toResponse)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("album", toResponse(album, user.id()));
        result.put("items", items);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<MediaResponse> upload(CurrentUser user, long albumId, MultipartFile[] files)
            throws IOException {
        requireManage(albumId, user);
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

        int sortOrder = medias.nextSortOrder(albumId);
        List<MediaResponse> uploaded = new ArrayList<>();
        for (MultipartFile file : validFiles) {
            Media media = mediaService.store(file, null, sortOrder++);
            // 同一相册中已有同 hash 文件时不再重复建立引用；这也能让
            // 上传响应丢失后的客户端重试保持幂等。
            java.util.Optional<Media> existing =
                    medias.findByHashInAlbum(albumId, media.contentHash());
            if (existing.isPresent() && existing.get().id() != media.id()) {
                mediaService.deleteFilesAndRecord(media);
                uploaded.add(mediaService.toResponse(existing.get()));
            } else {
                medias.addToAlbum(albumId, media.id(), media.sortOrder());
                uploaded.add(mediaService.toResponse(media));
            }
        }
        // 第一次上传时自动把第一张图设为封面；已有封面则不覆盖用户选择。
        Album album = albums.findById(albumId).orElseThrow();
        if (album.coverMediaId() == null && !uploaded.isEmpty()) {
            albums.updateCover(albumId, uploaded.get(0).id());
        }
        return uploaded;
    }

    /**
     * 从相册移除媒体：只解除关联。仅当该媒体不属于任何相册且不是朋友圈媒体时物理删除。
     */
    @Transactional
    public void removeMedia(CurrentUser user, long albumId, long mediaId) {
        requireManage(albumId, user);
        if (!medias.existsInAlbum(albumId, mediaId)) {
            throw new ApiException(404, "媒体不在该相册中");
        }
        medias.removeFromAlbum(albumId, mediaId);
        albums.clearCoverIfMatches(albumId, mediaId);

        Media media = medias.findById(mediaId).orElse(null);
        if (media != null && media.postId() == null && medias.countAlbumLinks(mediaId) == 0) {
            mediaService.deleteFilesAndRecord(media);
        }
    }

    /** 把媒体从当前相册移动到另一个相册；原文件不复制、不删除。 */
    @Transactional
    public AlbumResponse moveMedia(CurrentUser user, long sourceAlbumId, long mediaId,
                                   long targetAlbumId) {
        if (sourceAlbumId == targetAlbumId) {
            throw new ApiException(400, "不能移动到当前相册");
        }
        requireManage(sourceAlbumId, user);
        requireManage(targetAlbumId, user);
        if (!medias.existsInAlbum(sourceAlbumId, mediaId)) {
            throw new ApiException(404, "媒体不在当前相册中");
        }
        int sortOrder = medias.nextSortOrder(targetAlbumId);
        medias.addToAlbum(targetAlbumId, mediaId, sortOrder);
        medias.removeFromAlbum(sourceAlbumId, mediaId);
        albums.clearCoverIfMatches(sourceAlbumId, mediaId);
        Album target = albums.findById(targetAlbumId)
                .orElseThrow(() -> new ApiException(404, "目标相册不存在"));
        if (target.coverMediaId() == null) {
            albums.updateCover(targetAlbumId, mediaId);
            target = albums.findById(targetAlbumId).orElse(target);
        }
        return toResponse(target, user.id());
    }

    // ---- 分享私人相册 ----

    /** 当前分享名单（仅相册创建者可看）。 */
    public List<UserDto> shares(CurrentUser user, long albumId) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        return albums.findSharedUsers(albumId).stream()
                .map(u -> new UserDto(u.id(), u.userId(), u.nickname()))
                .toList();
    }

    /** 按登录 ID 分享给某个人，返回最新名单。 */
    @Transactional
    public List<UserDto> share(CurrentUser user, long albumId, String targetUserId) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        String normalized = targetUserId == null ? "" : targetUserId.trim();
        if (normalized.isEmpty()) {
            throw new ApiException(400, "请输入要分享的用户 ID");
        }
        User target = users.findByUserId(normalized)
                .orElseThrow(() -> new ApiException(404, "没有找到这个用户"));
        if (target.id() == user.id()) {
            throw new ApiException(400, "不能分享给自己");
        }
        albums.addShare(albumId, target.id());
        return shares(user, albumId);
    }

    /** 取消分享，返回最新名单。 */
    @Transactional
    public List<UserDto> unshare(CurrentUser user, long albumId, String targetUserId) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        User target = users.findByUserId(targetUserId == null ? "" : targetUserId.trim())
                .orElseThrow(() -> new ApiException(404, "没有找到这个用户"));
        albums.removeShare(albumId, target.id());
        return shares(user, albumId);
    }

    /**
     * 清理当前相册内的重复媒体：按 SHA-256 分组，每组保留一条引用。
     * 只删除多余的引用记录；物理文件在没有其他记录引用时才删除。
     */
    @Transactional
    public Map<String, Object> dedupe(CurrentUser user, long albumId) {
        Album album = requireManage(albumId, user);
        List<Media> items = medias.findByAlbum(albumId);
        Map<String, List<Media>> groups = new LinkedHashMap<>();
        for (Media media : items) {
            String hash = mediaService.ensureHash(media);
            String key = hash == null || hash.isBlank()
                    ? "path:" + media.path()
                    : hash;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(media);
        }

        int removed = 0;
        int duplicateGroups = 0;
        for (List<Media> group : groups.values()) {
            if (group.size() < 2) continue;
            duplicateGroups++;
            Media keeper = group.stream()
                    .filter(media -> album.coverMediaId() != null
                            && album.coverMediaId() == media.id())
                    .findFirst()
                    .orElse(group.get(0));
            for (Media media : group) {
                if (media.id() == keeper.id()) continue;
                medias.removeFromAlbum(albumId, media.id());
                if (album.coverMediaId() != null
                        && album.coverMediaId() == media.id()) {
                    albums.updateCover(albumId, keeper.id());
                }
                if (media.postId() == null
                        && medias.countAlbumLinks(media.id()) == 0) {
                    mediaService.deleteFilesAndRecord(media);
                }
                removed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", items.size());
        result.put("groups", duplicateGroups);
        result.put("removed", removed);
        return result;
    }

    // ---- 权限 ----

    /** 可见即可读；不可见统一按「不存在」处理，避免泄露相册是否存在。 */
    private Album requireView(long albumId, CurrentUser user) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
        if (!albums.canView(albumId, user.id())) {
            throw new ApiException(404, "相册不存在");
        }
        return album;
    }

    /**
     * 可修改：家庭公共相册沿用旧行为（谁都能改）；
     * 私人相册只有创建者能改，被分享的人只读。
     */
    private Album requireManage(long albumId, CurrentUser user) {
        Album album = requireView(albumId, user);
        if (album.isPrivate()
                && (album.ownerId() == null || album.ownerId() != user.id())) {
            throw new ApiException(403, "只有相册创建者可以操作");
        }
        return album;
    }

    private Album requireOwner(long albumId, CurrentUser user) {
        Album album = requireView(albumId, user);
        if (album.ownerId() == null || album.ownerId() != user.id()) {
            throw new ApiException(403, "只有相册创建者可以操作");
        }
        return album;
    }

    private void requirePrivate(Album album) {
        if (!album.isPrivate()) {
            throw new ApiException(400, "家庭相册默认所有人可见，不需要分享");
        }
    }

    private AlbumResponse toResponse(Album album, long currentUserId) {
        boolean isOwner = album.ownerId() != null && album.ownerId() == currentUserId;
        return new AlbumResponse(
                album.id(),
                album.title(),
                album.coverThumb(),
                album.mediaCount(),
                album.createdAt(),
                album.visibility(),
                album.ownerId(),
                album.ownerNickname(),
                isOwner,
                album.shareCount()
        );
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new ApiException(400, "相册标题不能为空");
        }
        String normalized = title.trim();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new ApiException(400, "相册标题不能超过 " + MAX_TITLE_LENGTH + " 个字");
        }
        return normalized;
    }
}
