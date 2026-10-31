package com.domovoi4ik.symphony.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Огибающая для рисования волны: максимум |сэмпла| в каждой из n полос (0…1). */
public final class Peaks {
    private Peaks() { }
    public static float[] compute(File f, int n) throws IOException {
        Wav.Info info = Wav.read(f);
        long frames = info.frames();
        float[] out = new float[n];
        if (frames == 0 || n <= 0) return out;
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(info.dataOffset);
            int ch = info.channels, bpf = ch * 2;
            byte[] buf = new byte[32768 - 32768 % bpf];
            long fr = 0;
            while (fr < frames) {
                int want = (int) Math.min(buf.length / bpf, frames - fr);
                r.readFully(buf, 0, want * bpf);
                for (int i = 0; i < want; i++) {
                    int p = i * bpf;
                    int s = Math.abs((short) ((buf[p] & 0xFF) | (buf[p + 1] << 8)));
                    int b = (int) ((fr + i) * n / frames);
                    float v = s / 32768f;
                    if (v > out[b]) out[b] = v;
                }
                fr += want;
            }
        } finally { r.close(); }
        return out;
    }
}
