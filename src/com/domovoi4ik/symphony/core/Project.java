package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.dsp.FxChain;

import java.util.Arrays;

/** Проект: темп, размер, дорожки (до 8), мастер-цепочка эффектов. */
public final class Project {
    public static final int SR = 44100, PPQ = 480, MAX_TRACKS = 8;
    public volatile int bpm = 110, beats = 4;
    public volatile boolean metronome = true;
    public volatile int masterVolume = 85;
    public volatile int quantize = 4;          // сетка: делений на долю (4 — шестнадцатые), 0 — без квантования
    private volatile Track[] tracks = new Track[0];
    public final FxChain master = new FxChain();
    private int nextId = 1;

    public Track[] tracks() { return tracks; }
    public int count() { return tracks.length; }
    public synchronized Track add(int type, String name) {
        if (tracks.length >= MAX_TRACKS) return null;
        Track t = new Track(nextId++, type, name);
        tracks = append(tracks, t);
        return t;
    }
    public synchronized void addLoaded(Track t) { tracks = append(tracks, t); nextId = Math.max(nextId, t.id + 1); }
    public synchronized void remove(Track t) {
        Track[] a = tracks; int k = -1;
        for (int i = 0; i < a.length; i++) if (a[i] == t) k = i;
        if (k < 0) return;
        Track[] b = new Track[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) if (i != k) b[j++] = a[i];
        tracks = b;
    }
    public synchronized void move(Track t, int dir) {
        Track[] a = tracks.clone();
        for (int i = 0; i < a.length; i++) if (a[i] == t) {
            int j = i + dir; if (j < 0 || j >= a.length) return;
            a[i] = a[j]; a[j] = t; tracks = a; return;
        }
    }
    public Track byId(int id) { for (Track t : tracks) if (t.id == id) return t; return null; }
    static Track[] append(Track[] a, Track t) { Track[] b = Arrays.copyOf(a, a.length + 1); b[a.length] = t; return b; }

    public double framesPerTick() { return 60.0 * SR / (bpm * (double) PPQ); }
    public long tickToFrame(long tick) { return Math.round(tick * framesPerTick()); }
    public long frameToTick(long frame) { return (long) Math.floor(frame / framesPerTick()); }
    public long framesPerBar() { return tickToFrame((long) PPQ * beats); }

    /** Длина песни: конец последней записи или ноты. */
    public long lengthFrames() {
        long m = 0;
        for (Track t : tracks) {
            if (t.type == Track.AUDIO) { if (t.frames > 0) m = Math.max(m, t.offset + t.frames); }
            else m = Math.max(m, tickToFrame(t.lastTick()));
        }
        return m;
    }
    /** Квантование тика к сетке проекта. */
    public int snap(long tick) {
        if (quantize <= 0) return (int) tick;
        int g = PPQ / quantize;
        return (int) (Math.round(tick / (double) g) * g);
    }
    public int gridTicks() { return PPQ / Math.max(1, quantize); }
}
