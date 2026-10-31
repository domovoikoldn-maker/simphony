package com.domovoi4ik.symphony.dsp;

/**
 * Цепочка эффектов. Массив заменяется целиком при добавлении/удалении (copy-on-write),
 * поэтому звуковой поток перебирает его без блокировок и без выделения памяти.
 */
public final class FxChain {
    public static final int MAX = 6;
    private volatile Effect[] fx = new Effect[0];
    /** Темп для эффектов «в такт» (дилей, тремоло). Ставит движок. */
    public volatile float bpm = 120;

    public Effect[] list() { return fx; }
    public int size() { return fx.length; }
    public synchronized boolean add(Effect e) {
        if (e == null || fx.length >= MAX) return false;
        Effect[] a = java.util.Arrays.copyOf(fx, fx.length + 1);
        a[fx.length] = e;
        fx = a;
        return true;
    }
    public synchronized void remove(Effect e) {
        Effect[] a = fx;
        int k = -1;
        for (int i = 0; i < a.length; i++) if (a[i] == e) k = i;
        if (k < 0) return;
        Effect[] b = new Effect[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) if (i != k) b[j++] = a[i];
        fx = b;
    }
    public synchronized void move(Effect e, int dir) {
        Effect[] a = fx.clone();
        for (int i = 0; i < a.length; i++) if (a[i] == e) {
            int j = i + dir;
            if (j < 0 || j >= a.length) return;
            a[i] = a[j]; a[j] = e; fx = a; return;
        }
    }
    public synchronized void clear() { fx = new Effect[0]; }
    public void reset() { for (Effect e : fx) e.reset(); }
    public boolean active() { for (Effect e : fx) if (e.on) return true; return false; }
    public void process(float[] l, float[] r, int off, int n) {
        Effect[] a = fx;
        for (Effect e : a) {
            if (e instanceof Synced) ((Synced) e).bpm(bpm);
            e.process(l, r, off, n);
        }
    }
    /** Эффекты, которым нужен темп проекта. */
    public interface Synced { void bpm(float bpm); }
}
