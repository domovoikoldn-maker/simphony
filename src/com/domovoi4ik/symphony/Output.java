package com.domovoi4ik.symphony;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import com.domovoi4ik.symphony.core.Engine;
import com.domovoi4ik.symphony.core.Mix;
import com.domovoi4ik.symphony.core.Project;

/**
 * Звуковой выход: один AudioTrack (стерео, 16 бит, 44,1 кГц, режим низкой задержки) и поток,
 * который просит у движка блоки по 256 кадров (~6 мс). Поток работает, пока что-то звучит
 * (транспорт, живые ноты, хвосты эффектов) или пока открыт экран инструмента, и сам засыпает в тишине.
 */
public final class Output {
    static final int BLOCK = 256;
    final Engine engine;
    final Studio studio;
    private Thread thread;
    private volatile boolean keepAlive;
    private volatile int latencyFrames;

    Output(Engine e, Studio s) { engine = e; studio = s; }

    /** Экран инструмента открыт: держать выход открытым, чтобы клавиши звучали без задержки на запуск. */
    public void setKeepAlive(boolean on) { keepAlive = on; if (on) ensureRunning(); }
    /** Сколько кадров между расчётом и динамиком (для точного курсора). */
    public int latencyFrames() { return latencyFrames; }

    public synchronized void ensureRunning() {
        if (thread != null) return;
        thread = new Thread(new Loop(this), "symphony-out");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    static final class Loop implements Runnable {
        final Output o;
        Loop(Output o) { this.o = o; }
        @Override public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioTrack t = null;
            try {
                int min = AudioTrack.getMinBufferSize(Project.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Project.SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM);
                if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                t = b.build();
                if (t.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("звук недоступен");
                o.latencyFrames = Math.max(min, BLOCK * 16) / 4;
                t.play();
                float[] l = new float[BLOCK], r = new float[BLOCK];
                short[] pcm = new short[BLOCK * 2];
                int idle = 0;
                while (true) {
                    boolean busy = o.engine.busy();
                    if (!busy && !o.keepAlive) {
                        if (++idle > 40) {                                   // ~0,25 с тишины — выходим
                            synchronized (o) {                               // …если никто не успел нажать ноту
                                if (!o.engine.busy() && !o.keepAlive) { o.thread = null; break; }
                            }
                            idle = 0;
                        }
                    } else idle = 0;
                    o.engine.render(l, r, BLOCK);
                    String err = o.engine.takeError();
                    if (err != null) o.studio.ui.post(new Err(o.studio, err));
                    for (int i = 0, j = 0; i < BLOCK; i++) { pcm[j++] = Mix.toShort(l[i]); pcm[j++] = Mix.toShort(r[i]); }
                    int w = t.write(pcm, 0, pcm.length);
                    if (w < 0) throw new IllegalStateException("ошибка звука " + w);
                }
            } catch (RuntimeException e) {
                synchronized (o) { o.thread = null; }
                o.studio.ui.post(new Err(o.studio, "Звук остановлен: " + e.getMessage()));
                o.engine.stop();
            } finally {
                if (t != null) { try { t.pause(); t.flush(); t.stop(); } catch (RuntimeException ignored) { } t.release(); }
            }
        }
    }
    static final class Err implements Runnable {
        final Studio s; final String m;
        Err(Studio s, String m) { this.s = s; this.m = m; }
        @Override public void run() { s.outputError(m); }
    }
}
