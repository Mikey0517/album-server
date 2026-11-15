package com.example.album.dto;

import java.util.List;

/**
 * 编辑已发布动态：文案与媒体都可以改，但发布时间永远不改。
 *
 * <p>三个字段都可选，缺省表示「这一项不动」：
 * <ul>
 *   <li>{@code content}       —— 新文案；空串按「清空文案」处理，不传则文案不变（只改媒体时用）。</li>
 *   <li>{@code media_ids}     —— 最终保留的媒体及顺序（整组替换）。被移除的只是解绑，
 *                                不删文件，仍被其他动态/相册引用的文件照旧。</li>
 *   <li>{@code add_media_ids} —— 新上传的媒体，按数组顺序挂到末尾；先应用 media_ids 再追加。</li>
 * </ul>
 * posted_at 不在请求体里：编辑不改变发布时间。
 */
public record UpdatePostRequest(
        String content,
        List<Long> mediaIds,
        List<Long> addMediaIds
) {
}
