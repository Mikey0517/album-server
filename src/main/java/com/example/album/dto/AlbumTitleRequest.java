package com.example.album.dto;

/** 新建相册：private 为 true 时创建私人相册（仅自己可见，可分享给指定用户）。 */
public record AlbumTitleRequest(String title, Boolean isPrivate) {
}
