/*
 * «Симфония» — простая многодорожечная студия. Разработчик и владелец: domovoi4ik.
 *
 * Wav — чтение и запись WAV (PCM 16 бит). Чистая Java: без Android, проверяется тестами на ПК.
 */
package com.domovoi4ik.symphony.audio;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public final class Wav {
    /** Формат всех дорожек и экспорта: 44,1 кГц, моно, 16 бит (44,1 кГц Android гарантирует на любом телефоне). */
    public static final int RATE = 44100, CHANNELS = 1, BITS = 16, HEADER = 44;
    public static final int BYTES_PER_FRAME = CHANNELS * BITS / 8;

    private Wav() { }

    /** Заголовок 44 байта для PCM; dataBytes — размер звуковых данных. */
    public static byte[] header(long dataBytes, int rate, int channels) {
        byte[] h = new byte[HEADER];
        int blockAlign = channels * 2;
        put4(h, 0, "RIFF"); le32(h, 4, 36 + dataBytes); put4(h, 8, "WAVE");
        put4(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, channels);
        le32(h, 24, rate); le32(h, 28, (long) rate * blockAlign); le16(h, 32, blockAlign); le16(h, 34, 16);
        put4(h, 36, "data"); le32(h, 40, dataBytes);
        return h;
    }
    public static void writeHeader(OutputStream out, long dataBytes) throws IOException { out.write(header(dataBytes, RATE, CHANNELS)); }

    /** После записи: вписать настоящие размеры в заголовок. */
    public static void fixSizes(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "rw");
        try {
            long data = Math.max(0, r.length() - HEADER);
            byte[] b = new byte[4];
            le32(b, 0, 36 + data); r.seek(4); r.write(b);
            le32(b, 0, data); r.seek(40); r.write(b);
        } finally { r.close(); }
    }

    /** Где в файле звук и сколько его. Разбирает блоки RIFF, а не верит 44 байтам. */
    public static final class Info {
        public long dataOffset, dataBytes;
        public int rate, channels, bits;
        public long frames() { return dataBytes / Math.max(1, channels * bits / 8); }
    }
    public static Info read(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[12];
            r.readFully(b);
            if (!"RIFF".equals(str(b, 0)) || !"WAVE".equals(str(b, 8))) throw new IOException("не WAV: " + f.getName());
            Info info = new Info();
            boolean fmt = false;
            long pos = 12, len = r.length();
            byte[] ch = new byte[8];
            while (pos + 8 <= len) {
                r.seek(pos); r.readFully(ch);
                String id = str(ch, 0);
                long size = u32(ch, 4);
                if ("fmt ".equals(id)) {
                    byte[] fb = new byte[16]; r.readFully(fb);
                    if (u16(fb, 0) != 1) throw new IOException("поддерживается только PCM");
                    info.channels = u16(fb, 2); info.rate = (int) u32(fb, 4); info.bits = u16(fb, 14);
                    fmt = true;
                } else if ("data".equals(id)) {
                    info.dataOffset = pos + 8;
                    info.dataBytes = Math.min(size, len - info.dataOffset);   // файл мог оборваться
                    if (!fmt) throw new IOException("нет блока fmt");
                    if (info.bits != 16) throw new IOException("нужны 16 бит");
                    return info;
                }
                pos += 8 + size + (size & 1);
            }
            throw new IOException("нет звуковых данных");
        } finally { r.close(); }
    }

    static String str(byte[] b, int o) { return new String(b, o, 4, java.nio.charset.StandardCharsets.US_ASCII); }
    static void put4(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    static long u32(byte[] b, int o) { return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24; }
}
