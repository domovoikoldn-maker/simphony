package com.domovoi4ik.symphony.dsp;

/** Фильтр: низких/высоких/полосовой, с резонансом и «вау»-модуляцией частоты (LFO). */
public final class Filter extends Effect {
    static final String[] KIND = {"Срез верхов (НЧ)", "Срез низов (ВЧ)", "Полосовой"};
    private final Biquad f = new Biquad();
    private int kind, cnt;
    private float cut, q, lfoDepth;
    private double ph, inc;
    public Filter() { super(Effects.FILTER, new float[]{0f, 0.7f, 0.2f, 0f, 0.4f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Тип", "Частота", "Резонанс", "Вау (глубина)", "Вау (скорость)"}; }
    int kindIdx() { return Math.min(2, (int) (p[0] * 3)); }
    @Override public String format(int i) {
        switch (i) {
            case 0: return KIND[kindIdx()];
            case 1: return hz(expo(40, 18000, p[1]));
            case 2: return String.format(java.util.Locale.US, "Q %.1f", 0.5f + p[2] * 11.5f);
            case 4: return String.format(java.util.Locale.US, "%.2f Гц", expo(0.05f, 8, p[4]));
            default: return pct(p[3]);
        }
    }
    @Override public void reset() { f.reset(); }
    @Override protected void update() {
        int k = kindIdx(); kind = k == 0 ? Biquad.LOWPASS : (k == 1 ? Biquad.HIGHPASS : Biquad.BANDPASS);
        cut = p[1]; q = 0.5f + p[2] * 11.5f; lfoDepth = p[3]; inc = 2 * Math.PI * expo(0.05f, 8, p[4]) / SR;
        f.set(kind, expo(40, 18000, cut), q, 0);
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        if (lfoDepth < 0.01f) { f.run(l, r, off, n); return; }
        for (int i = off, e = off + n; i < e; i += 32) {
            int m = Math.min(32, e - i);
            float t = Math.max(0, Math.min(1, cut + lfoDepth * 0.5f * (float) Math.sin(ph)));
            f.set(kind, expo(40, 18000, t), q, 0);
            f.run(l, r, i, m);
            ph += inc * m; if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
        }
    }
}
