package com.example.album.dto;

import java.util.List;

/**
 * 批量删除发布版本请求：{"version_codes":[1,2,3]}。
 * 全局 Jackson 使用 SNAKE_CASE，字段 versionCodes 对应 JSON 的 version_codes。
 */
public record BatchDeleteReleasesRequest(List<Integer> versionCodes) {
}
