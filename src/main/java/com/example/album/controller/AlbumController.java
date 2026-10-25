package com.example.album.controller;

import com.example.album.dto.AlbumPatchRequest;
import com.example.album.dto.AlbumResponse;
import com.example.album.dto.AlbumTitleRequest;
import com.example.album.dto.MediaResponse;
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

    /** 相册列表（含封面与媒体数量）。 */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("items", albumService.list());
    }

    /** 新建相册。 */
    @PostMapping
    public AlbumResponse create(@RequestBody AlbumTitleRequest request) {
        return albumService.create(request == null ? null : request.title());
    }

    /** 重命名或设置封面。 */
    @PatchMapping("/{id}")
    public AlbumResponse patch(@PathVariable long id, @RequestBody AlbumPatchRequest request) {
        return albumService.patch(id, request);
    }

    /** 删除相册并清理不再被任何相册引用的独立媒体。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        albumService.delete(id);
        return Map.of("ok", true);
    }

    /** 相册详情：{album, items}，items 按 sort_order, added_at。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return albumService.detail(id);
    }

    /** 相册上传：落盘、缩略图、写 media(post_id=NULL) 与 album_media。 */
    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @PathVariable long id,
            @RequestPart(value = "files", required = false) MultipartFile[] files) throws IOException {
        List<MediaResponse> items = albumService.upload(id, files);
        return Map.of("items", items);
    }

    /** 从相册移除媒体；只有独立且无其他相册引用的媒体会物理删除。 */
    @DeleteMapping("/{id}/media/{mediaId}")
    public Map<String, Object> removeMedia(@PathVariable long id, @PathVariable long mediaId) {
        albumService.removeMedia(id, mediaId);
        return Map.of("ok", true);
    }
}
