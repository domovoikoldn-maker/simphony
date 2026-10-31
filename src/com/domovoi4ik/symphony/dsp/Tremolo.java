package com.domovoi4ik.symphony.dsp;

/** Тремоло в темп: пульсация громкости (или перекатывание по стерео — «авто-панорама»). */
public final class Tremolo extends Effect implements FxChain.Synced {
    static final String[] NOTE = {"1/2", "1/4", "1/8", "1/8 триоль", "1/16"};
    static final float[] PER_BEAT = {0.5f, 1, 2, 3, 4};
    private float bpm = 120, depth;
    private boolean pan;
    private double ph, inc;
    public Tremolo() { super(Effects.TREMOLO, new float[]{0.5f, 0.6f, 0f, 0.3f}); }
    @Override public void bpm(float b) { if (b != bpm) { bpm = b; update(); } }
    int idx() { return Math.min(NOTE.length - 1, (int) (p[0] * NOTE.length)); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Скорость (в такт)", "Глубина", "Авто-панорама", "Мягкость"}; }
    @Override public String format(int i) { return i == 0 ? NOTE[idx()] : (i == 2 ? (p[2] > 0.5f ? "вкл" : "выкл") : pct(p[i])); }
    @Override public void reset() { }
    @Override protected void update() { inc = 2 * Math.PI * PER_BEAT[idx()] * bpm / 60 / SR; depth = p[1]; pan = p[2] > 0.5f; }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        float soft = 0.3f + p[3] * 8;
        for (int i = off, e = off + n; i < e; i++) {
            float s = (float) Math.tanh(Math.sin(ph) * soft) / (float) Math.tanh(soft);   // от синуса к «квадрату»
            float g = 1 - depth * (0.5f + 0.5f * s);
            if (pan) { float g2 = 1 - depth * (0.5f - 0.5f * s); l[i] *= g; r[i] *= g2; }
            else { l[i] *= g; r[i] *= g; }
            ph += inc; if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
        }
    }
}
