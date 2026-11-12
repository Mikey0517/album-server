package com.example.album.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 只解析 JPEG 的 APP1 Exif 段，取出 GPS 拍摄地点，不依赖第三方库。
 *
 * 全程防御式：非 JPEG、没有 Exif、GPS 字段越界或格式异常都返回 null，
 * 绝不向调用方抛异常，避免影响上传。为了不给大图增加 IO，
 * 最多只读文件头部 {@link #HEAD_BYTES} 字节。
 */
public final class ExifGeo {

    private static final int HEAD_BYTES = 256 * 1024;

    private static final int TAG_GPS_IFD = 0x8825;
    private static final int TAG_GPS_LATITUDE_REF = 0x0001;
    private static final int TAG_GPS_LATITUDE = 0x0002;
    private static final int TAG_GPS_LONGITUDE_REF = 0x0003;
    private static final int TAG_GPS_LONGITUDE = 0x0004;
    private static final int TAG_GPS_ALTITUDE_REF = 0x0005;
    private static final int TAG_GPS_ALTITUDE = 0x0006;

    private static final int TYPE_BYTE = 1;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_SBYTE = 6;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_SSHORT = 8;
    private static final int TYPE_SLONG = 9;
    private static final int TYPE_SRATIONAL = 10;
    private static final int TYPE_FLOAT = 11;
    private static final int TYPE_DOUBLE = 12;

    private static final int RATIONAL_BYTES = 8;
    private static final int ENTRY_BYTES = 12;

    private ExifGeo() {
    }

    /** 拍摄地点：latitude/longitude 为十进制度（南纬、西经为负），altitude 单位米，可缺省。 */
    public record GeoPoint(double latitude, double longitude, Double altitude) {
    }

    /** 读取原图的 GPS 信息；任何情况都只是返回 null，不会抛异常。 */
    public static GeoPoint read(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return parse(readHead(file));
        } catch (Exception e) {
            // 解析失败一律当作「没有拍摄地点」，上传流程不能因此失败。
            return null;
        }
    }

    private static byte[] readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = new byte[HEAD_BYTES];
            int total = 0;
            int read;
            while (total < head.length
                    && (read = in.read(head, total, head.length - total)) != -1) {
                total += read;
            }
            return total == head.length ? head : Arrays.copyOf(head, total);
        }
    }

    private static GeoPoint parse(byte[] data) {
        if (data.length < 4) {
            return null;
        }
        // JPEG 必须以 SOI(FF D8) 开头，PNG/WebP/视频等格式在这里就被挡掉。
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        int tiff = findExifTiff(data);
        if (tiff < 0) {
            return null;
        }
        return parseTiff(data, tiff);
    }

    /** 顺序扫描 JPEG 段，返回 Exif TIFF header 的偏移；没有 APP1 Exif 时返回 -1。 */
    private static int findExifTiff(byte[] data) {
        int pos = 2;
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return -1;
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            // TEM、RSTn、SOI 没有长度字段。
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                pos += 2;
                continue;
            }
            // 到了图像数据或文件结尾，Exif 只可能出现在更前面。
            if (marker == 0xD9 || marker == 0xDA) {
                return -1;
            }
            int length = u16(data, pos + 2);
            if (length < 2 || pos + 2 + length > data.length) {
                return -1;
            }
            if (marker == 0xE1 && length >= 8
                    && data[pos + 4] == 'E' && data[pos + 5] == 'x'
                    && data[pos + 6] == 'i' && data[pos + 7] == 'f'
                    && data[pos + 8] == 0 && data[pos + 9] == 0) {
                return pos + 10;
            }
            pos += 2 + length;
        }
        return -1;
    }

    private static GeoPoint parseTiff(byte[] data, int tiff) {
        if (tiff + 8 > data.length) {
            return null;
        }
        boolean little;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
            little = true;
        } else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
            little = false;
        } else {
            return null;
        }
        Tiff t = new Tiff(data, tiff, little);
        if (t.u16(tiff + 2) != 0x2A) {
            return null;
        }
        int ifd0 = t.absolute(t.u32(tiff + 4));
        int gpsPointer = t.findEntry(ifd0, TAG_GPS_IFD);
        if (gpsPointer < 0) {
            return null;
        }
        // GPS IFD 指针是 LONG(1)，偏移值直接放在 entry 的 4 字节值域里。
        int gpsIfd = t.absolute(t.u32(gpsPointer + 8));

        int latitudeEntry = t.findEntry(gpsIfd, TAG_GPS_LATITUDE);
        int longitudeEntry = t.findEntry(gpsIfd, TAG_GPS_LONGITUDE);
        if (latitudeEntry < 0 || longitudeEntry < 0) {
            return null;
        }
        double latitude = t.degrees(latitudeEntry);
        double longitude = t.degrees(longitudeEntry);

        int latitudeRefEntry = t.findEntry(gpsIfd, TAG_GPS_LATITUDE_REF);
        int longitudeRefEntry = t.findEntry(gpsIfd, TAG_GPS_LONGITUDE_REF);
        if (latitudeRefEntry >= 0 && t.asciiChar(latitudeRefEntry, 0) == 'S') {
            latitude = -latitude;
        }
        if (longitudeRefEntry >= 0 && t.asciiChar(longitudeRefEntry, 0) == 'W') {
            longitude = -longitude;
        }
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return null;
        }
        return new GeoPoint(latitude, longitude, readAltitude(t, gpsIfd));
    }

    /** 海拔只是附带信息，解析失败时保持 null，不影响经纬度。 */
    private static Double readAltitude(Tiff t, int gpsIfd) {
        try {
            int entry = t.findEntry(gpsIfd, TAG_GPS_ALTITUDE);
            if (entry < 0) {
                return null;
            }
            double value = t.rational(t.valueAt(entry, t.u16(entry + 2), t.u32(entry + 4)));
            int refEntry = t.findEntry(gpsIfd, TAG_GPS_ALTITUDE_REF);
            // GPSAltitudeRef：0 = 海平面以上，1 = 海平面以下。
            int ref = refEntry < 0 ? 0 : t.byteValue(refEntry);
            return ref == 1 ? -value : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            throw new IllegalArgumentException("JPEG 段越界");
        }
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    /** 一个 TIFF 结构（起点 + 字节序），所有方法都按数组下标读取。 */
    private static final class Tiff {

        private final byte[] data;
        private final int base;
        private final boolean little;

        private Tiff(byte[] data, int base, boolean little) {
            this.data = data;
            this.base = base;
            this.little = little;
        }

        private void require(int abs, int length) {
            if (abs < 0 || length < 0 || abs + length > data.length) {
                throw new IllegalArgumentException("Exif 偏移越界");
            }
        }

        private int u16(int abs) {
            require(abs, 2);
            int b0 = data[abs] & 0xFF;
            int b1 = data[abs + 1] & 0xFF;
            return little ? (b1 << 8 | b0) : (b0 << 8 | b1);
        }

        private long u32(int abs) {
            require(abs, 4);
            long b0 = data[abs] & 0xFFL;
            long b1 = data[abs + 1] & 0xFFL;
            long b2 = data[abs + 2] & 0xFFL;
            long b3 = data[abs + 3] & 0xFFL;
            return little
                    ? (b3 << 24 | b2 << 16 | b1 << 8 | b0)
                    : (b0 << 24 | b1 << 16 | b2 << 8 | b3);
        }

        /** TIFF 里的偏移都相对 header 起点，这里换算成数组下标。 */
        private int absolute(long relative) {
            long abs = base + relative;
            if (relative < 0 || abs < base || abs >= data.length) {
                throw new IllegalArgumentException("Exif 偏移越界");
            }
            return (int) abs;
        }

        private int typeSize(int type) {
            return switch (type) {
                case TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1;
                case TYPE_SHORT, TYPE_SSHORT -> 2;
                case TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4;
                case TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8;
                default -> 0;
            };
        }

        /** IFD 里指定 tag 的 entry 下标；找不到返回 -1。 */
        private int findEntry(int ifd, int tag) {
            int count = u16(ifd);
            if (count <= 0) {
                return -1;
            }
            require(ifd + 2, count * ENTRY_BYTES);
            for (int i = 0; i < count; i++) {
                int entry = ifd + 2 + i * ENTRY_BYTES;
                if (u16(entry) == tag) {
                    return entry;
                }
            }
            return -1;
        }

        /** tag 的值在数组里的起始下标：不超过 4 字节时内联在 entry 里。 */
        private int valueAt(int entry, int type, long count) {
            int size = typeSize(type);
            if (size == 0 || count <= 0) {
                throw new IllegalArgumentException("Exif 值类型不支持: " + type);
            }
            long total = (long) size * count;
            if (total <= 4) {
                return entry + 8;
            }
            return absolute(u32(entry + 8));
        }

        private char asciiChar(int entry, int index) {
            int type = u16(entry + 2);
            long count = u32(entry + 4);
            if (type != TYPE_ASCII || index < 0 || index >= count) {
                throw new IllegalArgumentException("Exif ASCII 字段格式不支持");
            }
            return (char) (data[valueAt(entry, type, count) + index] & 0xFF);
        }

        /** 单字节类型字段（如 GPSAltitudeRef）的整数值。 */
        private int byteValue(int entry) {
            int type = u16(entry + 2);
            long count = u32(entry + 4);
            if (type != TYPE_BYTE && type != TYPE_SBYTE && type != TYPE_UNDEFINED) {
                throw new IllegalArgumentException("Exif 单字节字段格式不支持");
            }
            return data[valueAt(entry, type, count)] & 0xFF;
        }

        /** GPS 度分秒（3 个 RATIONAL）换算成十进制度。 */
        private double degrees(int entry) {
            int type = u16(entry + 2);
            long count = u32(entry + 4);
            if (type != TYPE_RATIONAL || count < 3) {
                throw new IllegalArgumentException("Exif GPS 坐标格式不支持");
            }
            int value = valueAt(entry, type, count);
            double degrees = rational(value);
            double minutes = rational(value + RATIONAL_BYTES);
            double seconds = rational(value + 2 * RATIONAL_BYTES);
            if (minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) {
                throw new IllegalArgumentException("Exif GPS 分秒越界");
            }
            return degrees + minutes / 60.0 + seconds / 3600.0;
        }

        private double rational(int abs) {
            long numerator = u32(abs);
            long denominator = u32(abs + 4);
            return denominator == 0 ? 0 : (double) numerator / (double) denominator;
        }
    }
}
