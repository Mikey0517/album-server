package com.example.album.dto;

/** 编辑动态：只改文案，媒体不在这里换（换图等于换动态）。 */
public record UpdatePostRequest(String content) {
}
