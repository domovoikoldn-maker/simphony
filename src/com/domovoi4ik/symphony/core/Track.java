package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.dsp.FxChain;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Дорожка: аудио (запись с микрофона) или инструмент (ноты + встроенный синтезатор).
 * У каждой — громкость, панорама, mute/solo и цепочка эффектов.
 * Поля читает звуковой поток, поэтому они volatile, а ноты заменяются массивом целиком (copy-on-write).
 */
public final class Track {
    public static final int AUDIO = 0, INSTRUMENT = 1;
    public final int id;
    public final int type;
    public volatile String name;
    public volatile int volume = 80;          // 0…100
    public volatile int pan;                  // −100 (лево) … 100 (право)
    public volatile boolean mute, solo;
    public volatile int instrument;           // Synth.PIANO…
    public volatile String file;              // аудио: имя WAV в папке проекта
    public volatile long offset;              // аудио: с какого кадра песни начинается запись
    public volatile long frames;              // аудио: длина записи (заполняется при загрузке)
    private volatile Note[] notes = new Note[0];
    public final FxChain fx = new FxChain();

    public Track(int id, int type, String name) { this.id = id; this.type = type; this.name = name; }

    public boolean isAudio() { return type == AUDIO; }
    public Note[] notes() { return notes; }
    public void setNotes(Note[] n) { Note[] c = n.clone(); Note.sort(c); notes = c; }
    public void setNotes(ArrayList<Note> n) { setNotes(n.toArray(new Note[0])); }
    public void addNote(Note n) { Note[] a = Arrays.copyOf(notes, notes.length + 1); a[a.length - 1] = n; setNotes(a); }
    public void removeNote(Note n) {
        Note[] a = notes; int k = -1;
        for (int i = 0; i < a.length; i++) if (a[i] == n) k = i;
        if (k < 0) return;
        Note[] b = new Note[a.length - 1];
        System.arraycopy(a, 0, b, 0, k); System.arraycopy(a, k + 1, b, k, a.length - k - 1);
        notes = b;
    }
    public void replaceNote(Note old, Note now) {
        Note[] a = notes.clone();
        for (int i = 0; i < a.length; i++) if (a[i] == old) { a[i] = now; setNotes(a); return; }
    }
    /** Последний тик, где звучит нота (0 — нот нет). */
    public int lastTick() { int m = 0; for (Note n : notes) m = Math.max(m, n.end()); return m; }
    public boolean hasContent() { return type == AUDIO ? frames > 0 : notes.length > 0; }

    /** Множитель громкости: квадратичная шкала (середина ползунка ≈ −12 дБ, как на пультах). */
    public static float gainOf(int slider) { float v = Math.max(0, Math.min(100, slider)) / 100f; return v * v * 1.25f; }
    /** Панорама «баланс»: в центре оба канала 1.0. */
    public float gainL() { return gainOf(volume) * Math.min(1, 1 - pan / 100f); }
    public float gainR() { return gainOf(volume) * Math.min(1, 1 + pan / 100f); }
}
