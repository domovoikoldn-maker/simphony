package com.domovoi4ik.symphony.dsp;

/** Хорус: задержка ~15 мс, модулируемая синусом; левый и правый каналы со сдвигом фазы 90°. */
public final class Chorus extends Effect {
    private final float[] bl = new float[2048], br = new float[2048];
    private int w;
    private double ph, inc;
    private float depth, mix;
    public Chorus() { super(Effects.CHORUS, new float[]{0.3f, 0.5f, 0.5f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Скорость", "Глубина", "Микс"}; }
    @Override public String format(int i) { return i == 0 ? String.format(java.util.Locale.US, "%.2f Гц", expo(0.1f, 5, p[0])) : pct(p[i]); }
    @Override public void reset() { java.util.Arrays.fill(bl, 0); java.util.Arrays.fill(br, 0); }
    @Override protected void update() { inc = 2 * Math.PI * expo(0.1f, 5, p[0]) / SR; depth = (0.5f + p[1] * 7.5f) * SR / 1000; mix = p[2]; }
    static float tap(float[] b, int w, float d) {
        float pos = w - d; if (pos < 0) pos += b.length;
        int i = (int) pos; float f = pos - i; int j = i + 1; if (j >= b.length) j = 0;
        return b[i] + (b[j] - b[i]) * f;
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        float base = 0.015f * SR;
        for (int i = off, e = off + n; i < e; i++) {
            bl[w] = l[i]; br[w] = r[i];
            float dL = base + depth * (float) Math.sin(ph), dR = base + depth * (float) Math.cos(ph);
            float yl = tap(bl, w, dL), yr = tap(br, w, dR);
            l[i] = l[i] * (1 - mix * 0.5f) + yl * mix;
            r[i] = r[i] * (1 - mix * 0.5f) + yr * mix;
            if (++w >= bl.length) w = 0;
            ph += inc; if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
        }
    }
}
