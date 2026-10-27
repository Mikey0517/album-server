package com.example.album.service;

import com.example.album.dto.AlbumPatchRequest;
import com.example.album.dto.AlbumResponse;
import com.example.album.dto.MediaResponse;
import com.example.album.exception.ApiException;
import com.example.album.model.Album;
import com.example.album.model.Media;
import com.example.album.repository.AlbumRepository;
import com.example.album.repository.MediaRepository;
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

    private final AlbumRepository albums;
    private final MediaRepository medias;
    private final MediaService mediaService;

    public AlbumService(AlbumRepository albums,
                        MediaRepository medias,
                        MediaService mediaService) {
        this.albums = albums;
        this.medias = medias;
        this.mediaService = mediaService;
    }

    public List<AlbumResponse> list() {
        return albums.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AlbumResponse create(String title) {
        String normalized = normalizeTitle(title);
        long id = albums.insert(normalized);
        return albums.findById(id).map(this::toResponse)
                .orElseThrow(() -> new ApiException(500, "创建相册失败"));
    }

    @Transactional
    public AlbumResponse patch(long albumId, AlbumPatchRequest request) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
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
        return albums.findById(albumId).map(this::toResponse)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
    }

    /**
     * 删除相册 + 清理孤儿媒体：
     * 只物理删除 post_id 为 NULL 且已不再属于任何相册的媒体；
     * 朋友圈媒体即使被误关联，也保留原记录与文件。
     */
    @Transactional
    public void delete(long albumId) {
        if (!albums.exists(albumId)) {
            throw new ApiException(404, "相册不存在");
        }
        List<Media> candidates = medias.findOrphanCandidatesByAlbum(albumId);
        albums.delete(albumId); // CASCADE 删除 album_media
        for (Media media : candidates) {
            if (medias.countAlbumLinks(media.id()) == 0) {
                mediaService.deleteFilesAndRecord(media);
            }
        }
    }

    public Map<String, Object> detail(long albumId) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
        List<MediaResponse> items = medias.findByAlbum(albumId).stream()
                .map(mediaService::toResponse)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("album", toResponse(album));
        result.put("items", items);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<MediaResponse> upload(long albumId, MultipartFile[] files) throws IOException {
        if (!albums.exists(albumId)) {
            throw new ApiException(404, "相册不存在");
        }
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
    public void removeMedia(long albumId, long mediaId) {
        if (!albums.exists(albumId)) {
            throw new ApiException(404, "相册不存在");
        }
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

    /**
     * 清理当前相册内的重复媒体：按 SHA-256 分组，每组保留一条引用。
     * 只删除多余的引用记录；物理文件在没有其他记录引用时才删除。
     */
    @Transactional
    public Map<String, Object> dedupe(long albumId) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new ApiException(404, "相册不存在"));
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

    private AlbumResponse toResponse(Album album) {
        return new AlbumResponse(
                album.id(),
                album.title(),
                album.coverThumb(),
                album.mediaCount(),
                album.createdAt()
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
