package com.domovoi4ik.symphony.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Потоковое чтение одной дорожки (PCM 16 бит, моно или стерео → моно) с любого места.
 * Память — только буфер 16 КБ, поэтому длинные записи не грузятся целиком.
 */
public final class TrackReader {
    private final RandomAccessFile raf;
    private final Wav.Info info;
    private final byte[] buf = new byte[16384];
    private long frame;
    public final long frames;

    public TrackReader(File f, long startFrame) throws IOException {
        info = Wav.read(f);
        frames = info.frames();
        raf = new RandomAccessFile(f, "r");
        seek(startFrame);
    }
    public void seek(long startFrame) throws IOException {
        frame = Math.max(0, Math.min(frames, startFrame));
        raf.seek(info.dataOffset + frame * info.channels * 2);
    }
    public boolean ended() { return frame >= frames; }

    /** Прибавить n кадров дорожки × gain к acc. Возвращает, сколько кадров было на самом деле. */
    public int addTo(float[] acc, int n, float gain) throws IOException {
        int ch = info.channels, bpf = ch * 2, done = 0;
        while (done < n && frame < frames) {
            int want = (int) Math.min(Math.min(n - done, buf.length / bpf), frames - frame);
            raf.readFully(buf, 0, want * bpf);
            float g = gain / (32768f * ch);
            for (int i = 0, p = 0; i < want; i++) {
                int s = 0;
                for (int c = 0; c < ch; c++, p += 2) s += (short) ((buf[p] & 0xFF) | (buf[p + 1] << 8));
                acc[done + i] += s * g;
            }
            done += want;
            frame += want;
        }
        return done;
    }
    /** Записать (не прибавить) n кадров моно в dst[off…]; возвращает, сколько было, остаток — нули. */
    public int readMono(float[] dst, int off, int n) throws IOException {
        int ch = info.channels, bpf = ch * 2, done = 0;
        float g = 1f / (32768f * ch);
        while (done < n && frame < frames) {
            int want = (int) Math.min(Math.min(n - done, buf.length / bpf), frames - frame);
            raf.readFully(buf, 0, want * bpf);
            for (int i = 0, p = 0; i < want; i++) {
                int s = 0;
                for (int c = 0; c < ch; c++, p += 2) s += (short) ((buf[p] & 0xFF) | (buf[p + 1] << 8));
                dst[off + done + i] = s * g;
            }
            done += want;
            frame += want;
        }
        for (int i = done; i < n; i++) dst[off + i] = 0;
        return done;
    }
    public long position() { return frame; }
    public void close() { try { raf.close(); } catch (IOException ignored) { } }
}
