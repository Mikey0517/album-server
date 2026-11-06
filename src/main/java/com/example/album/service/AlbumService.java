package com.example.album.service;

import com.example.album.dto.AlbumPatchRequest;
import com.example.album.dto.AlbumResponse;
import com.example.album.dto.AlbumTitleRequest;
import com.example.album.dto.MediaResponse;
import com.example.album.dto.SharedUserDto;
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
import java.util.Set;

@Service
public class AlbumService {

    private static final int MAX_FILES = 30;
    private static final int MAX_TITLE_LENGTH = 40;

    private static final String VISIBILITY_FAMILY = "family";
    private static final String VISIBILITY_PRIVATE = "private";

    /** viewer 只读；editor 可写（传/删照片）；admin 管理员（还能删相册）。 */
    public static final String ROLE_VIEWER = "viewer";
    public static final String ROLE_EDITOR = "editor";
    public static final String ROLE_ADMIN = "admin";
    private static final Set<String> SHARE_ROLES = Set.of(ROLE_VIEWER, ROLE_EDITOR, ROLE_ADMIN);

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
        Map<Long, String> roles = albums.findShareRolesForUser(user.id());
        return albums.findVisibleTo(user.id()).stream()
                .map(album -> toResponse(album, user.id(),
                        roleOf(album, user.id(), roles.get(album.id()))))
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
        return albums.findById(id)
                .map(album -> toResponse(album, user.id(), roleOf(album, user.id(), null)))
                .orElseThrow(() -> new ApiException(500, "创建相册失败"));
    }

    @Transactional
    public AlbumResponse patch(CurrentUser user, long albumId, AlbumPatchRequest request) {
        Album album = requireManage(albumId, user);
        if (request == null) {
            throw new ApiException(400, "请求参数不正确");
        }
        if (request.title() != null) {
            albums.updateTitle(albumId, normalizeTitle(request.title()));
        }
        if (request.coverMediaId() != null) {
            long mediaId = request.coverMediaId();
            if (medias.findById(mediaId).isEmpty() || !medias.existsInAlbum(albumId, mediaId)) {
                throw new ApiException(400, "封面媒体不属于该相册");
            }
            albums.updateCover(albumId, mediaId);
        }
        return albums.findById(albumId)
                .map(updated -> toResponse(updated, user.id(), roleOf(updated, user.id(), null)))
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
    }

    /**
     * 删除相册 + 清理孤儿媒体：只有创建者和管理员可以删。
     */
    @Transactional
    public void delete(CurrentUser user, long albumId) {
        requireManage(albumId, user);
        List<Media> candidates = medias.findOrphanCandidatesByAlbum(albumId);
        albums.delete(albumId);
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
        String role = roleOf(album, user.id(), albums.findShareRole(albumId, user.id()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("album", toResponse(album, user.id(), role));
        result.put("items", items);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<MediaResponse> upload(CurrentUser user, long albumId, MultipartFile[] files)
            throws IOException {
        requireEditMedia(albumId, user);
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
        Album album = albums.findById(albumId).orElseThrow();
        if (album.coverMediaId() == null && !uploaded.isEmpty()) {
            albums.updateCover(albumId, uploaded.get(0).id());
        }
        return uploaded;
    }

    @Transactional
    public void removeMedia(CurrentUser user, long albumId, long mediaId) {
        requireEditMedia(albumId, user);
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

    @Transactional
    public AlbumResponse moveMedia(CurrentUser user, long sourceAlbumId, long mediaId,
                                   long targetAlbumId) {
        if (sourceAlbumId == targetAlbumId) {
            throw new ApiException(400, "不能移动到当前相册");
        }
        requireEditMedia(sourceAlbumId, user);
        requireEditMedia(targetAlbumId, user);
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
        return toResponse(target, user.id(), roleOf(target, user.id(), null));
    }

    // ---- 分享私人相册 ----

    public List<SharedUserDto> shares(CurrentUser user, long albumId) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        return albums.findSharedUsers(albumId).stream()
                .map(shared -> new SharedUserDto(shared.userId(), shared.loginId(),
                        shared.nickname(), shared.role()))
                .toList();
    }

    /** 按登录 ID 分享给某个人并指定权限，默认只读。 */
    @Transactional
    public List<SharedUserDto> share(CurrentUser user, long albumId, String targetUserId,
                                     String role) {
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
        albums.upsertShare(albumId, target.id(), normalizeRole(role));
        return shares(user, albumId);
    }

    /** 修改已有分享的权限。 */
    @Transactional
    public List<SharedUserDto> updateShareRole(CurrentUser user, long albumId,
                                               String targetUserId, String role) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        User target = users.findByUserId(targetUserId == null ? "" : targetUserId.trim())
                .orElseThrow(() -> new ApiException(404, "没有找到这个用户"));
        if (albums.findShareRole(albumId, target.id()) == null) {
            throw new ApiException(404, "该用户不在分享名单里");
        }
        albums.upsertShare(albumId, target.id(), normalizeRole(role));
        return shares(user, albumId);
    }

    @Transactional
    public List<SharedUserDto> unshare(CurrentUser user, long albumId, String targetUserId) {
        Album album = requireOwner(albumId, user);
        requirePrivate(album);
        User target = users.findByUserId(targetUserId == null ? "" : targetUserId.trim())
                .orElseThrow(() -> new ApiException(404, "没有找到这个用户"));
        albums.removeShare(albumId, target.id());
        return shares(user, albumId);
    }

    @Transactional
    public Map<String, Object> dedupe(CurrentUser user, long albumId) {
        Album album = requireEditMedia(albumId, user);
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

    private Album requireView(long albumId, CurrentUser user) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
        if (!albums.canView(albumId, user.id())) {
            throw new ApiException(404, "相册不存在");
        }
        return album;
    }

    /** 上传/删除照片：创建者、管理员、可写，家庭相册所有人。 */
    private Album requireEditMedia(long albumId, CurrentUser user) {
        Album album = requireView(albumId, user);
        String role = roleOf(album, user.id(), albums.findShareRole(albumId, user.id()));
        if (!canEditMedia(role)) {
            throw new ApiException(403, "当前权限只能查看，不能修改照片");
        }
        return album;
    }

    /** 改名/封面/删除相册：创建者、管理员，家庭相册所有人。 */
    private Album requireManage(long albumId, CurrentUser user) {
        Album album = requireView(albumId, user);
        String role = roleOf(album, user.id(), albums.findShareRole(albumId, user.id()));
        if (!canManage(role)) {
            throw new ApiException(403, "只有创建者或管理员可以操作相册");
        }
        return album;
    }

    /** 分享名单只有创建者能改。 */
    private Album requireOwner(long albumId, CurrentUser user) {
        Album album = requireView(albumId, user);
        if (album.ownerId() == null || album.ownerId() != user.id()) {
            throw new ApiException(403, "只有相册创建者可以管理分享");
        }
        return album;
    }

    private void requirePrivate(Album album) {
        if (!album.isPrivate()) {
            throw new ApiException(400, "家庭相册默认所有人可见，不需要分享");
        }
    }

    private String roleOf(Album album, long userId, String shareRole) {
        if (!album.isPrivate()) return "family";
        if (album.ownerId() != null && album.ownerId() == userId) return "owner";
        return shareRole == null ? ROLE_VIEWER : shareRole;
    }

    private boolean canEditMedia(String role) {
        return "family".equals(role) || "owner".equals(role)
                || ROLE_EDITOR.equals(role) || ROLE_ADMIN.equals(role);
    }

    private boolean canManage(String role) {
        return "family".equals(role) || "owner".equals(role) || ROLE_ADMIN.equals(role);
    }

    private String normalizeRole(String role) {
        String value = role == null || role.isBlank() ? ROLE_VIEWER : role.trim();
        if (!SHARE_ROLES.contains(value)) {
            throw new ApiException(400, "权限只能是 viewer / editor / admin");
        }
        return value;
    }

    private AlbumResponse toResponse(Album album, long currentUserId, String role) {
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
                album.shareCount(),
                role
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
