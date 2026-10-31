package com.domovoi4ik.symphony.dsp;

/** Дилей в темп (от 1/16 до 1/2) или свободный, с пинг-понгом и фильтром в обратной связи. */
public final class Delay extends Effect implements FxChain.Synced {
    static final String[] NOTE = {"1/16", "1/8", "1/8 с точкой", "1/4", "1/4 с точкой", "1/2"};
    static final float[] BEATS = {0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f};
    private final float[] bl = new float[SR * 3], br = new float[SR * 3];
    private int w, len;
    private float bpm = 120, fbk, mix, lpL, lpR, lpa;
    private boolean ping;
    public Delay() { super(Effects.DELAY, new float[]{0.4f, 0.35f, 0.25f, 0f, 0.6f}); }
    @Override public void bpm(float b) { if (b != bpm) { bpm = b; update(); } }
    int noteIdx() { return Math.min(NOTE.length - 1, (int) (p[0] * NOTE.length)); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Время (в такт)", "Повторы", "Микс", "Пинг-понг", "Яркость"}; }
    @Override public String format(int i) {
        switch (i) {
            case 0: return NOTE[noteIdx()] + " · " + ms(BEATS[noteIdx()] * 60000 / bpm);
            case 3: return p[3] > 0.5f ? "вкл" : "выкл";
            case 4: return hz(expo(800, 16000, p[4]));
            default: return pct(p[i]);
        }
    }
    @Override public void reset() { java.util.Arrays.fill(bl, 0); java.util.Arrays.fill(br, 0); lpL = lpR = 0; }
    @Override protected void update() {
        len = Math.max(1, Math.min(bl.length - 1, (int) (BEATS[noteIdx()] * 60f / bpm * SR)));
        fbk = p[1] * 0.92f; mix = p[2]; ping = p[3] > 0.5f;
        lpa = (float) Math.exp(-2 * Math.PI * expo(800, 16000, p[4]) / SR);
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        int N = bl.length;
        for (int i = off, e = off + n; i < e; i++) {
            int rd = w - len; if (rd < 0) rd += N;
            float dl = bl[rd], dr = br[rd];
            lpL = dl + (lpL - dl) * lpa; lpR = dr + (lpR - dr) * lpa;
            float inL = l[i], inR = r[i];
            if (ping) { bl[w] = (inL + inR) * 0.5f + lpR * fbk; br[w] = lpL * fbk; }
            else { bl[w] = inL + lpL * fbk; br[w] = inR + lpR * fbk; }
            if (++w >= N) w = 0;
            l[i] = inL + dl * mix;
            r[i] = inR + dr * mix;
        }
        lpL = clean(lpL); lpR = clean(lpR);
    }
}
