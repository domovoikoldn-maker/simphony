package com.domovoi4ik.symphony.dsp;

/** Стерео-расширение: середина/бока (M/S) + небольшая задержка одного канала (эффект Хааса). */
public final class Widener extends Effect {
    private final float[] buf = new float[SR / 20];
    private int w, d;
    private float width;
    public Widener() { super(Effects.WIDENER, new float[]{0.6f, 0.2f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Ширина", "Хаас (задержка)"}; }
    @Override public String format(int i) { return i == 0 ? pct(p[0] * 2) : ms(p[1] * 25); }
    @Override public void reset() { java.util.Arrays.fill(buf, 0); }
    @Override protected void update() { width = p[0] * 2; d = (int) (p[1] * 0.025f * SR); }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        for (int i = off, e = off + n; i < e; i++) {
            float m = (l[i] + r[i]) * 0.5f, s = (l[i] - r[i]) * 0.5f * width;
            float L = m + s, R = m - s;
            buf[w] = R;
            int rd = w - d; if (rd < 0) rd += buf.length;
            if (++w >= buf.length) w = 0;
            l[i] = L; r[i] = d > 0 ? buf[rd] : R;
        }
    }
}
