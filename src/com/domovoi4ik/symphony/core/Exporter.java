package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.audio.Chime;
import com.domovoi4ik.symphony.audio.Wav;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Экспорт: весь проект сводится тем же движком, что и при прослушивании, в WAV 44,1 кГц / 16 бит / стерео.
 * Поток вывода может быть «только вперёд» (файл из системного диалога), поэтому длина считается
 * заранее и заголовок сразу пишется с верным размером. В конце — 2 с на «хвосты» реверберации/нот.
 * Без награды за рекламу в конец добавляется короткая тихая метка «Симфонии».
 */
public final class Exporter {
    public interface Progress { void onProgress(int percent); boolean cancelled(); }
    public static final int TAIL = Project.SR * 2;

    private Exporter() { }

    public static long export(Project p, File dir, OutputStream rawOut, boolean watermark, Progress pr) throws IOException {
        long body = p.lengthFrames();
        if (body == 0) return 0;
        long frames = body + TAIL;
        short[] chime = watermark ? Chime.make(Project.SR) : new short[0];
        long total = frames + chime.length;
        Engine e = new Engine(p, dir, true);
        try {
            OutputStream out = new BufferedOutputStream(rawOut, 1 << 16);
            out.write(Wav.header(total * 4, Project.SR, 2));
            int B = 4096;
            float[] l = new float[B], r = new float[B];
            byte[] bytes = new byte[B * 4];
            e.play(0);
            long done = 0;
            int last = -1;
            while (done < frames) {
                if (pr != null && pr.cancelled()) throw new IOException("отменено");
                int n = (int) Math.min(B, frames - done);
                e.render(l, r, n);
                String err = e.takeError();
                if (err != null) throw new IOException(err);
                for (int i = 0, j = 0; i < n; i++) {
                    short a = Mix.toShort(l[i]), b = Mix.toShort(r[i]);
                    bytes[j++] = (byte) a; bytes[j++] = (byte) (a >> 8); bytes[j++] = (byte) b; bytes[j++] = (byte) (b >> 8);
                }
                out.write(bytes, 0, n * 4);
                done += n;
                int pct = (int) (done * 100 / total);
                if (pr != null && pct != last) { pr.onProgress(pct); last = pct; }
            }
            byte[] cb = new byte[chime.length * 4];
            for (int i = 0, j = 0; i < chime.length; i++) { short s = chime[i]; cb[j++] = (byte) s; cb[j++] = (byte) (s >> 8); cb[j++] = (byte) s; cb[j++] = (byte) (s >> 8); }
            out.write(cb);
            out.flush();
            if (pr != null) pr.onProgress(100);
            return total;
        } finally {
            e.close();
        }
    }
}
