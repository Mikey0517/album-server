package com.example.album.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 全项目统一使用「本地时区 ISO8601、秒精度」字符串，例如 2026-10-24T22:01:10。
 * 统一格式保证 SQLite 文本排序与游标 (时间, id) 比较的结果正确。
 * 容器与宿主需设置相同 TZ（默认 Asia/Shanghai）。
 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.systemDefault();
    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter EXIF_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private TimeUtil() {
    }

    public static String now() {
        return LocalDateTime.now(ZONE).withNano(0).format(FORMATTER);
    }

    public static String format(LocalDateTime time) {
        return time.withNano(0).format(FORMATTER);
    }

    /** EXIF DateTimeOriginal: "2024:05:01 18:20:30" */
    public static String fromExif(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return format(LocalDateTime.parse(raw.trim(), EXIF_FORMATTER));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** ffprobe creation_time 通常是 ISO8601（可能是 UTC 的 Z 结尾）。 */
    public static String fromCreationTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return format(LocalDateTime.ofInstant(Instant.parse(value), ZONE));
        } catch (DateTimeParseException ignored) {
            // 继续尝试无时区格式
        }
        try {
            return format(LocalDateTime.parse(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 兼容旧数据中带时区/带毫秒的时间字符串。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return now();
        }
        try {
            return format(LocalDateTime.parse(raw));
        } catch (DateTimeParseException ignored) {
            // 继续尝试 Instant
        }
        try {
            return format(LocalDateTime.ofInstant(Instant.parse(raw), ZONE));
        } catch (DateTimeParseException e) {
            return now();
        }
    }
}
