package com.domovoi4ik.symphony.dsp;

/** Компрессор с общей для двух каналов огибающей (пиковый детектор, мягкое колено 6 дБ). */
public final class Compressor extends Effect {
    private float thr, ratio, att, rel, makeup, env;   // env — сглаженное подавление, дБ
    public Compressor() { super(Effects.COMP, new float[]{0.55f, 0.46f, 0.59f, 0.55f, 0.25f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Порог", "Степень", "Атака", "Восстановление", "Усиление"}; }
    @Override public String format(int i) {
        switch (i) {
            case 0: return dbs(p[0] * 40 - 40);
            case 1: return String.format(java.util.Locale.US, "%.1f : 1", expo(1, 20, p[1]));
            case 2: return ms(expo(0.5f, 80, p[2]));
            case 3: return ms(expo(20, 800, p[3]));
            default: return dbs(p[4] * 24);
        }
    }
    @Override public void reset() { env = 0; }
    @Override protected void update() {
        thr = p[0] * 40 - 40; ratio = expo(1, 20, p[1]); makeup = db(p[4] * 24);
        att = (float) Math.exp(-1.0 / (expo(0.5f, 80, p[2]) * 0.001 * SR));
        rel = (float) Math.exp(-1.0 / (expo(20, 800, p[3]) * 0.001 * SR));
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        float e = env, slope = 1 - 1 / ratio, knee = 6;
        for (int i = off, end = off + n; i < end; i++) {
            float x = Math.max(Math.abs(l[i]), Math.abs(r[i]));
            float lv = x < 1e-6f ? -120 : (float) (20 * Math.log10(x));
            float over = lv - thr, red;
            if (over <= -knee / 2) red = 0;
            else if (over >= knee / 2) red = over * slope;
            else { float k = over + knee / 2; red = slope * k * k / (2 * knee); }
            e = red > e ? att * e + (1 - att) * red : rel * e + (1 - rel) * red;
            float g = (float) Math.pow(10, -e / 20) * makeup;
            l[i] *= g; r[i] *= g;
        }
        env = clean(e);
    }
    /** Текущее подавление, дБ — для индикатора. */
    public float reduction() { return env; }
}
