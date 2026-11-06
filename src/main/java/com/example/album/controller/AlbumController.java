package com.example.album.controller;

import com.example.album.dto.AlbumPatchRequest;
import com.example.album.dto.AlbumResponse;
import com.example.album.dto.AlbumTitleRequest;
import com.example.album.dto.MediaResponse;
import com.example.album.dto.MoveMediaRequest;
import com.example.album.dto.ShareRequest;
import com.example.album.security.Auth;
import com.example.album.security.CurrentUser;
import com.example.album.service.AlbumService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/albums")
public class AlbumController {

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
        this.albumService = albumService;
    }

    /** 相册列表：家庭公共 + 自己创建的私人相册 + 分享给我的私人相册。 */
    @GetMapping
    public Map<String, Object> list(@Auth CurrentUser user) {
        return Map.of("items", albumService.list(user));
    }

    /** 新建相册；is_private=true 时创建私人相册。 */
    @PostMapping
    public AlbumResponse create(@Auth CurrentUser user,
                                @RequestBody AlbumTitleRequest request) {
        return albumService.create(user, request);
    }

    /** 重命名或设置封面。 */
    @PatchMapping("/{id}")
    public AlbumResponse patch(@Auth CurrentUser user,
                               @PathVariable long id,
                               @RequestBody AlbumPatchRequest request) {
        return albumService.patch(user, id, request);
    }

    /** 删除相册并清理不再被任何相册引用的独立媒体。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@Auth CurrentUser user, @PathVariable long id) {
        albumService.delete(user, id);
        return Map.of("ok", true);
    }

    /** 相册详情：{album, items}，items 按 sort_order, added_at。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@Auth CurrentUser user, @PathVariable long id) {
        return albumService.detail(user, id);
    }

    /** 相册上传：落盘、缩略图、写 media(post_id=NULL) 与 album_media。 */
    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @Auth CurrentUser user,
            @PathVariable long id,
            @RequestPart(value = "files", required = false) MultipartFile[] files) throws IOException {
        List<MediaResponse> items = albumService.upload(user, id, files);
        return Map.of("items", items);
    }

    /** 清理当前相册内 hash 相同的重复照片/视频，只保留一条引用。 */
    @PostMapping("/{id}/dedupe")
    public Map<String, Object> dedupe(@Auth CurrentUser user, @PathVariable long id) {
        return albumService.dedupe(user, id);
    }

    /** 把媒体移动到另一个相册。 */
    @PostMapping("/{sourceId}/media/{mediaId}/move")
    public AlbumResponse moveMedia(
            @Auth CurrentUser user,
            @PathVariable long sourceId,
            @PathVariable long mediaId,
            @RequestBody MoveMediaRequest request) {
        if (request == null || request.targetAlbumId() == null) {
            throw new com.example.album.exception.ApiException(400, "目标相册不能为空");
        }
        return albumService.moveMedia(user, sourceId, mediaId, request.targetAlbumId());
    }

    /** 从相册移除媒体；只有独立且无其他相册引用的媒体会物理删除。 */
    @DeleteMapping("/{id}/media/{mediaId}")
    public Map<String, Object> removeMedia(@Auth CurrentUser user,
                                           @PathVariable long id,
                                           @PathVariable long mediaId) {
        albumService.removeMedia(user, id, mediaId);
        return Map.of("ok", true);
    }

    // ---- 私人相册分享 ----

    /** 当前分享名单（仅相册创建者）。 */
    @GetMapping("/{id}/shares")
    public Map<String, Object> shares(@Auth CurrentUser user, @PathVariable long id) {
        return Map.of("items", albumService.shares(user, id));
    }

    /** 分享给某个用户（登录 ID）。 */
    @PostMapping("/{id}/shares")
    public Map<String, Object> share(@Auth CurrentUser user,
                                     @PathVariable long id,
                                     @RequestBody ShareRequest request) {
        return Map.of("items",
                albumService.share(user, id, request == null ? null : request.userId()));
    }

    /** 取消分享。 */
    @DeleteMapping("/{id}/shares/{userId}")
    public Map<String, Object> unshare(@Auth CurrentUser user,
                                       @PathVariable long id,
                                       @PathVariable String userId) {
        return Map.of("items", albumService.unshare(user, id, userId));
    }
}
