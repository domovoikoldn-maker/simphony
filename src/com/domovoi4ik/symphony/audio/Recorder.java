package com.domovoi4ik.symphony.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Запись с микрофона через AudioRecord прямо в WAV (PCM 16 бит, 44,1 кГц, моно).
 *
 * Почему AudioRecord, а не MediaRecorder: MediaRecorder отдаёт только сжатые AAC/AMR/3GP —
 * их пришлось бы декодировать для сведения, а начало записи «плавает» на десятки мс.
 * AudioRecord даёт сырые сэмплы: пишем WAV без перекодирования, точно знаем длину,
 * можем отрезать задержку и показывать уровень сигнала.
 *
 * Пишем во временный файл: если запись сорвалась, старый дубль дорожки остаётся цел.
 */
public final class Recorder {
    public interface Listener {
        /** Поток записи завершился. error == null — успех. Вызывается НЕ на UI-потоке. */
        void onRecordFinished(int track, File take, long frames, String error);
    }

    public static final int MAX_MINUTES = 30;
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;
    private volatile float level;
    private volatile long frames;
    private int track = -1;

    public boolean isRecording() { return running; }
    public int track() { return track; }
    /** Пик последнего блока 0…1 — для индикатора. */
    public float level() { return level; }
    public long frames() { return frames; }

    /**
     * Начать запись. dropMs — сколько миллисекунд срезать в начале (компенсация задержки,
     * чтобы новая дорожка совпала с уже записанными). Возвращает текст ошибки или null.
     */
    public String start(int trackIndex, File take, int dropMs, Listener l) {
        if (running) return "Запись уже идёт";
        int min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return "Телефон не поддерживает запись 44,1 кГц";
        AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, Wav.RATE / 5 * 2));
        } catch (SecurityException e) {
            return "Нет разрешения на микрофон";
        } catch (RuntimeException e) {
            return "Микрофон недоступен: " + e.getMessage();
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return "Микрофон недоступен"; }
        OutputStream out;
        try {
            out = new BufferedOutputStream(new FileOutputStream(take), 1 << 16);
            Wav.writeHeader(out, 0);                  // размеры впишем в конце
        } catch (IOException e) {
            r.release();
            return "Не удалось создать файл: " + e.getMessage();
        }
        try {
            r.startRecording();
        } catch (RuntimeException e) {
            r.release(); closeQuiet(out);
            return "Микрофон занят другим приложением";
        }
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); closeQuiet(out);
            return "Микрофон занят другим приложением";
        }
        rec = r;
        track = trackIndex;
        frames = 0;
        level = 0;
        running = true;
        thread = new Thread(new Loop(this, r, out, take, trackIndex, (long) dropMs * Wav.RATE / 1000, l), "record");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        return null;
    }

    /** Остановить; поток сам допишет файл и вызовет Listener. */
    public void stop() { running = false; }

    /** Остановить и дождаться окончания (при уходе приложения в фон). */
    public void stopAndWait() {
        running = false;
        Thread t = thread;
        if (t != null) try { t.join(2000); } catch (InterruptedException ignored) { }
    }

    static void closeQuiet(OutputStream o) { try { o.close(); } catch (IOException ignored) { } }

    static final class Loop implements Runnable {
        final Recorder rc; final AudioRecord r; final OutputStream out; final File take; final int track; final Listener l;
        long drop;
        Loop(Recorder rc, AudioRecord r, OutputStream out, File take, int track, long drop, Listener l) {
            this.rc = rc; this.r = r; this.out = out; this.take = take; this.track = track; this.drop = drop; this.l = l;
        }
        @Override public void run() {
            String error = null;
            short[] buf = new short[2048];
            byte[] bytes = new byte[buf.length * 2];
            long max = (long) MAX_MINUTES * 60 * Wav.RATE, written = 0;
            try {
                while (rc.running) {
                    int n = r.read(buf, 0, buf.length);
                    if (n < 0) { error = "Микрофон перестал отвечать (код " + n + ")"; break; }
                    int off = 0;
                    if (drop > 0) { int d = (int) Math.min(drop, n); drop -= d; off = d; }
                    int peak = 0;
                    for (int i = off, j = 0; i < n; i++) {
                        short s = buf[i];
                        int a = s < 0 ? -s : s;
                        if (a > peak) peak = a;
                        bytes[j++] = (byte) s; bytes[j++] = (byte) (s >> 8);
                    }
                    if (n > 0) rc.level = peak / 32768f;
                    if (n - off > 0) out.write(bytes, 0, (n - off) * 2);
                    written += n - off;
                    rc.frames = written;
                    if (written >= max) { error = "Достигнут предел " + MAX_MINUTES + " минут — запись остановлена и сохранена"; break; }
                }
            } catch (IOException e) {
                error = "Ошибка записи файла (нет места?): " + e.getMessage();
            } finally {
                rc.running = false;
                try { r.stop(); } catch (RuntimeException ignored) { }
                r.release();
                closeQuiet(out);
                try { Wav.fixSizes(take); } catch (IOException e) { if (error == null) error = "Не удалось сохранить файл: " + e.getMessage(); }
                rc.rec = null;
                rc.track = -1;
            }
            if (l != null) l.onRecordFinished(track, take, written, error);
        }
    }
}
