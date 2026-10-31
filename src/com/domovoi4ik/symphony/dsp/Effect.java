/*
 * «Симфония» — встроенные эффекты («плагины»). Разработчик и владелец: domovoi4ik.
 *
 * Effect — один эффект в цепочке дорожки или мастера. Параметры хранятся нормированными 0…1
 * (их двигают ползунки), каждый эффект сам переводит их в Гц, дБ, мс. Ползунки можно двигать
 * во время воспроизведения: эффект пересчитывает коэффициенты в начале блока.
 * Обработка — стерео, «на месте» (in-place), без выделения памяти в звуковом потоке.
 */
package com.domovoi4ik.symphony.dsp;

public abstract class Effect {
    public static final int SR = 44100;
    public final int type;
    public volatile boolean on = true;
    public final float[] p;
    private final float[] seen;

    protected Effect(int type, float[] defaults) {
        this.type = type;
        this.p = defaults.clone();
        this.seen = new float[defaults.length];
        java.util.Arrays.fill(seen, Float.NaN);
    }

    public abstract String name();
    public abstract String[] paramNames();
    /** Подпись значения параметра i, например «−3,0 дБ». */
    public abstract String format(int i);
    /** Сбросить хвосты (при перемотке/остановке). */
    public abstract void reset();
    /** Пересчитать коэффициенты из p[]. */
    protected abstract void update();
    protected abstract void run(float[] l, float[] r, int off, int n);

    public final void process(float[] l, float[] r, int off, int n) {
        if (!on) return;
        boolean changed = false;
        for (int i = 0; i < p.length; i++) if (p[i] != seen[i]) { seen[i] = p[i]; changed = true; }
        if (changed) update();
        run(l, r, off, n);
    }

    // ---- общие помощники ----
    protected static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    /** Экспоненциальная шкала: 0 → a, 1 → b (для частот и времени). */
    protected static float expo(float a, float b, float t) { return (float) (a * Math.pow(b / a, t)); }
    protected static float db(float d) { return (float) Math.pow(10, d / 20); }
    protected static String hz(float f) { return f >= 1000 ? String.format(java.util.Locale.US, "%.1f кГц", f / 1000) : Math.round(f) + " Гц"; }
    protected static String dbs(float d) { return String.format(java.util.Locale.US, "%+.1f дБ", d).replace('-', '−'); }
    protected static String pct(float v) { return Math.round(v * 100) + "%"; }
    protected static String ms(float v) { return v >= 1000 ? String.format(java.util.Locale.US, "%.2f с", v / 1000) : Math.round(v) + " мс"; }
    /** Защита от денормализованных чисел и «взрывов» (NaN/бесконечность → 0). */
    protected static float clean(float x) { return (x > -1e-15f && x < 1e-15f) || x != x || Math.abs(x) > 1e4f ? 0 : x; }
}
