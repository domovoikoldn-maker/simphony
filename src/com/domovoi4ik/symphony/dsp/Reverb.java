package com.domovoi4ik.symphony.dsp;

/** Реверберация по схеме Freeverb (8 гребенчатых + 4 всепропускающих фильтра на канал) с предзадержкой. */
public final class Reverb extends Effect {
    static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static final int[] AP = {556, 441, 341, 225};
    static final int SPREAD = 23;
    private final float[][] cbL = new float[8][], cbR = new float[8][], apL = new float[4][], apR = new float[4][];
    private final int[] ciL = new int[8], ciR = new int[8], aiL = new int[4], aiR = new int[4];
    private final float[] fsL = new float[8], fsR = new float[8];
    private final float[] pre = new float[SR / 10 + 1];
    private int preI, preLen;
    private float fb, damp, wet1, wet2, dry, wetGain;

    public Reverb() {
        super(Effects.REVERB, new float[]{0.6f, 0.5f, 0.3f, 1f, 0.2f});
        for (int i = 0; i < 8; i++) { cbL[i] = new float[COMB[i]]; cbR[i] = new float[COMB[i] + SPREAD]; }
        for (int i = 0; i < 4; i++) { apL[i] = new float[AP[i]]; apR[i] = new float[AP[i] + SPREAD]; }
    }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Размер", "Глушение верхов", "Микс", "Ширина", "Предзадержка"}; }
    @Override public String format(int i) { return i == 4 ? ms(p[4] * 100) : pct(p[i]); }
    @Override public void reset() {
        for (float[] a : cbL) java.util.Arrays.fill(a, 0); for (float[] a : cbR) java.util.Arrays.fill(a, 0);
        for (float[] a : apL) java.util.Arrays.fill(a, 0); for (float[] a : apR) java.util.Arrays.fill(a, 0);
        java.util.Arrays.fill(fsL, 0); java.util.Arrays.fill(fsR, 0); java.util.Arrays.fill(pre, 0);
    }
    @Override protected void update() {
        fb = 0.7f + 0.28f * p[0];
        damp = p[1] * 0.4f;
        float wet = p[2] * 3, width = p[3];
        wet1 = wet * (width / 2 + 0.5f); wet2 = wet * ((1 - width) / 2);
        dry = 1 - p[2] * 0.5f;
        wetGain = 0.015f;
        preLen = Math.max(1, (int) (p[4] * SR / 10));
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        for (int i = off, e = off + n; i < e; i++) {
            float in = (l[i] + r[i]) * wetGain;
            pre[preI] = in;
            int rd = preI - preLen + 1; if (rd < 0) rd += pre.length;
            in = pre[rd];
            if (++preI >= pre.length) preI = 0;
            float oL = 0, oR = 0;
            for (int c = 0; c < 8; c++) {
                float[] b = cbL[c]; int k = ciL[c];
                float y = b[k]; fsL[c] = y * (1 - damp) + fsL[c] * damp; b[k] = in + fsL[c] * fb; if (++k >= b.length) k = 0; ciL[c] = k; oL += y;
                b = cbR[c]; k = ciR[c];
                y = b[k]; fsR[c] = y * (1 - damp) + fsR[c] * damp; b[k] = in + fsR[c] * fb; if (++k >= b.length) k = 0; ciR[c] = k; oR += y;
            }
            for (int a = 0; a < 4; a++) {
                float[] b = apL[a]; int k = aiL[a];
                float bo = b[k]; b[k] = oL + bo * 0.5f; oL = bo - oL; if (++k >= b.length) k = 0; aiL[a] = k;
                b = apR[a]; k = aiR[a];
                bo = b[k]; b[k] = oR + bo * 0.5f; oR = bo - oR; if (++k >= b.length) k = 0; aiR[a] = k;
            }
            l[i] = l[i] * dry + oL * wet1 + oR * wet2;
            r[i] = r[i] * dry + oR * wet1 + oL * wet2;
        }
        for (int c = 0; c < 8; c++) { fsL[c] = clean(fsL[c]); fsR[c] = clean(fsR[c]); }
    }
}
