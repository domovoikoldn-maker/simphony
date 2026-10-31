package com.domovoi4ik.symphony.dsp;

/** Перегруз: усиление → мягкое насыщение (tanh, слегка несимметричное) → тембр → уровень. */
public final class Drive extends Effect {
    private final Biquad tone = new Biquad();
    private float gain, mix, out;
    public Drive() { super(Effects.DRIVE, new float[]{0.4f, 0.6f, 1f, 0.6f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Перегруз", "Тембр", "Микс", "Громкость"}; }
    @Override public String format(int i) {
        switch (i) { case 0: return dbs(p[0] * 36); case 1: return hz(expo(1000, 16000, p[1])); case 3: return dbs(p[3] * 18 - 12); default: return pct(p[2]); }
    }
    @Override public void reset() { tone.reset(); }
    @Override protected void update() { gain = db(p[0] * 36); mix = p[2]; out = db(p[3] * 18 - 12); tone.set(Biquad.LOWPASS, expo(1000, 16000, p[1]), 0.7f, 0); }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        float comp = 1 / (float) Math.tanh(Math.max(1, gain) * 0.5f + 0.5f);   // примерно держим громкость
        for (int i = off, e = off + n; i < e; i++) {
            float x = l[i], y = r[i];
            l[i] = shape(x * gain) * comp; r[i] = shape(y * gain) * comp;
            l[i] = x + (l[i] - x) * mix; r[i] = y + (r[i] - y) * mix;
        }
        tone.run(l, r, off, n);
        for (int i = off, e = off + n; i < e; i++) { l[i] *= out; r[i] *= out; }
    }
    static float shape(float x) { return (float) Math.tanh(x + 0.1f * x * x / (1 + Math.abs(x))); }
}
