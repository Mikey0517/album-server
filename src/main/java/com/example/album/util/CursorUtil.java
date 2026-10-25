package com.example.album.util;

import com.example.album.exception.ApiException;

/**
 * 游标格式 "<ISO时间>_<id>"。ISO 时间本身不含下划线，因此从最后一个下划线切分即可。
 */
public final class CursorUtil {

    private CursorUtil() {
    }

    public record Cursor(String time, long id) {
    }

    public static Cursor parse(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int separator = cursor.lastIndexOf('_');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new ApiException(400, "游标格式不正确");
        }
        String time = cursor.substring(0, separator);
        try {
            long id = Long.parseLong(cursor.substring(separator + 1));
            if (id <= 0) {
                throw new NumberFormatException("id must be positive");
            }
            return new Cursor(time, id);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "游标格式不正确");
        }
    }

    public static String build(String time, long id) {
        return time + "_" + id;
    }
}
