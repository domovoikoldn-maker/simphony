package com.domovoi4ik.symphony.dsp;

/** Лоу-фай: понижение разрядности и частоты дискретизации. */
public final class BitCrush extends Effect {
    private int hold, cnt;
    private float step, hl, hr, mix;
    public BitCrush() { super(Effects.BITCRUSH, new float[]{0.5f, 0.3f, 1f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Разрядность", "Частота", "Микс"}; }
    int bits() { return Math.round(16 - p[0] * 13); }
    int div() { return 1 + Math.round(p[1] * p[1] * 40); }
    @Override public String format(int i) { return i == 0 ? bits() + " бит" : (i == 1 ? hz(SR / (float) div()) : pct(p[2])); }
    @Override public void reset() { cnt = 0; hl = hr = 0; }
    @Override protected void update() { step = 2f / (1 << bits()); hold = div(); mix = p[2]; }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        for (int i = off, e = off + n; i < e; i++) {
            if (cnt-- <= 0) { cnt = hold - 1; hl = Math.round(l[i] / step) * step; hr = Math.round(r[i] / step) * step; }
            l[i] += (hl - l[i]) * mix; r[i] += (hr - r[i]) * mix;
        }
    }
}
