package com.domovoi4ik.symphony.core;

/** Нота инструментальной дорожки. Время — в тиках (480 на четверть), поэтому ноты идут за темпом. Неизменяемая. */
public final class Note {
    public final int start, len, pitch, vel;
    public Note(int start, int len, int pitch, int vel) {
        this.start = Math.max(0, start);
        this.len = Math.max(1, len);
        this.pitch = Math.max(0, Math.min(127, pitch));
        this.vel = Math.max(1, Math.min(127, vel));
    }
    public int end() { return start + len; }
    /** Порядок: по времени, затем по высоте. */
    public static boolean before(Note a, Note b) { return a.start != b.start ? a.start < b.start : a.pitch < b.pitch; }
    /** Устойчивая сортировка слиянием (без Comparator — так собирается и старым D8). */
    public static void sort(Note[] a) {
        if (a.length < 2) return;
        Note[] tmp = new Note[a.length];
        for (int w = 1; w < a.length; w *= 2)
            for (int lo = 0; lo < a.length - w; lo += 2 * w) {
                int mid = lo + w, hi = Math.min(lo + 2 * w, a.length), i = lo, j = mid, k = lo;
                while (i < mid && j < hi) tmp[k++] = before(a[j], a[i]) ? a[j++] : a[i++];
                while (i < mid) tmp[k++] = a[i++];
                while (j < hi) tmp[k++] = a[j++];
                System.arraycopy(tmp, lo, a, lo, hi - lo);
            }
    }
}
