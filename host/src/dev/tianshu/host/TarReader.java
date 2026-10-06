package dev.tianshu.host;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 最小 tar 读取器。只解析 rootfs 归档实际会用到的条目类型：
 * 普通文件 / 目录 / 符号链接 / 硬链接 / GNU 长名（L、K）/ PAX 扩展头（x、g）。
 *
 * 符号链接必须原样重建 —— rootfs 里 /bin -> usr/bin 这类链接是树的骨架，
 * 解成普通文件整棵树就废了。
 */
public final class TarReader {

    public static final class Entry {
        public String name;
        public char type;       // '0' 文件 '5' 目录 '2' 符号链接 '1' 硬链接
        public long size;
        public int mode;
        public String linkName;

        public boolean isRegularFile() {
            return type == '0' || type == 0 || type == '7';
        }

        public boolean isDirectory() { return type == '5'; }

        public boolean isSymlink() { return type == '2'; }

        public boolean isHardLink() { return type == '1'; }
    }

    private static final int BLOCK = 512;

    private TarReader() {
    }

    /** 读下一个有效条目；归档结束（全零块 / 流尽）返回 null。 */
    public static Entry readHeader(InputStream in) throws IOException {
        byte[] h = new byte[BLOCK];
        if (!readFully(in, h)) return null;
        if (isZeroBlock(h)) return null;

        String pendingName = null;
        String pendingLink = null;

        while (true) {
            char type = (char) (h[156] & 0xff);
            long size = num(h, 124, 12);
            int mode = (int) num(h, 100, 8);
            String link = str(h, 157, 100);

            if (type == 'L' || type == 'K') {           // GNU 长名 / 长链接名
                String v = new String(readData(in, size), "UTF-8").replace("\0", "");
                if (type == 'L') pendingName = v;
                else pendingLink = v;
                if (!readFully(in, h)) return null;
                if (isZeroBlock(h)) return null;
                continue;
            }

            if (type == 'x' || type == 'g') {           // PAX 扩展头
                String[] kv = parsePax(readData(in, size));
                if (kv[0] != null) pendingName = kv[0];
                if (kv[1] != null) pendingLink = kv[1];
                if (!readFully(in, h)) return null;
                if (isZeroBlock(h)) return null;
                continue;
            }

            Entry e = new Entry();
            e.name = pendingName != null ? pendingName : joinPrefix(str(h, 345, 155), str(h, 0, 100));
            e.type = type;
            e.size = size;
            e.mode = mode;
            e.linkName = pendingLink != null ? pendingLink : link;
            return e;
        }
    }

    /** 读走 size 字节数据（含 padding），返回内容。 */
    public static byte[] readData(InputStream in, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE - 8) throw new IOException("条目过大: " + size);
        byte[] data = new byte[(int) size];
        if (!readFully(in, data)) throw new EOFException("tar 数据被截断");
        skipFully(in, padding(size));
        return data;
    }

    /** 跳过条目数据（含 padding）。 */
    public static void skipData(InputStream in, long size) throws IOException {
        skipFully(in, size + padding(size));
    }

    // ---------------------------------------------------------------- 内部
    private static long padding(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static String joinPrefix(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String[] parsePax(byte[] data) throws IOException {
        String[] out = new String[2];
        String text = new String(data, "UTF-8");
        for (String line : text.split("\n")) {
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String kv = line.substring(sp + 1);
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if (k.equals("path")) out[0] = v;
            else if (k.equals("linkpath")) out[1] = v;
        }
        return out;
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    private static String str(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** tar 数字字段：ASCII 八进制，或首字节高位为 1 时的 base-256。 */
    private static long num(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {                     // base-256
            long v = h[off] & 0x7f;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (h[i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') break;
            v = (v << 3) + (b - '0');
        }
        return v;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            // 2026-10-04 深查：这里原是 `off > 0 ? false : false`（两个分支同值 = 死代码，
            // 掩盖了"到流尾 → 这次读不完整"的本意）。另外 `n == 0` 时 off 不前进，
            // 万一遇到不前进的流就是**空转死循环** —— 一并当成"读不动"收掉。
            if (n < 0) return false;      // 到流尾
            if (n == 0) return false;     // 不前进的读：再转就是死循环
            off += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int got = in.read(buf, 0, want);
            if (got < 0) return;
            left -= got;
        }
    }
}
