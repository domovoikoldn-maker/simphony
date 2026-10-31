package com.domovoi4ik.symphony.dsp;

/** Двухполюсный фильтр (формулы RBJ «Audio EQ Cookbook»), стерео. */
public final class Biquad {
    public static final int LOWPASS = 0, HIGHPASS = 1, PEAK = 2, LOWSHELF = 3, HIGHSHELF = 4, BANDPASS = 5;
    float b0 = 1, b1, b2, a1, a2;
    float l1, l2, r1, r2;   // состояние (Direct Form II transposed)

    public void set(int kind, float freq, float q, float gainDb) {
        double w = 2 * Math.PI * Math.min(freq, Effect.SR * 0.45) / Effect.SR, cw = Math.cos(w), sw = Math.sin(w);
        double A = Math.pow(10, gainDb / 40), alpha = sw / (2 * Math.max(0.05, q));
        double B0, B1, B2, A0, A1, A2;
        switch (kind) {
            case LOWPASS: B0 = (1 - cw) / 2; B1 = 1 - cw; B2 = B0; A0 = 1 + alpha; A1 = -2 * cw; A2 = 1 - alpha; break;
            case HIGHPASS: B0 = (1 + cw) / 2; B1 = -(1 + cw); B2 = B0; A0 = 1 + alpha; A1 = -2 * cw; A2 = 1 - alpha; break;
            case BANDPASS: B0 = alpha; B1 = 0; B2 = -alpha; A0 = 1 + alpha; A1 = -2 * cw; A2 = 1 - alpha; break;
            case PEAK: B0 = 1 + alpha * A; B1 = -2 * cw; B2 = 1 - alpha * A; A0 = 1 + alpha / A; A1 = -2 * cw; A2 = 1 - alpha / A; break;
            case LOWSHELF: {
                double s = 2 * Math.sqrt(A) * alpha;
                B0 = A * ((A + 1) - (A - 1) * cw + s); B1 = 2 * A * ((A - 1) - (A + 1) * cw); B2 = A * ((A + 1) - (A - 1) * cw - s);
                A0 = (A + 1) + (A - 1) * cw + s; A1 = -2 * ((A - 1) + (A + 1) * cw); A2 = (A + 1) + (A - 1) * cw - s; break;
            }
            default: {
                double s = 2 * Math.sqrt(A) * alpha;
                B0 = A * ((A + 1) + (A - 1) * cw + s); B1 = -2 * A * ((A - 1) + (A + 1) * cw); B2 = A * ((A + 1) + (A - 1) * cw - s);
                A0 = (A + 1) - (A - 1) * cw + s; A1 = 2 * ((A - 1) - (A + 1) * cw); A2 = (A + 1) - (A - 1) * cw - s; break;
            }
        }
        b0 = (float) (B0 / A0); b1 = (float) (B1 / A0); b2 = (float) (B2 / A0); a1 = (float) (A1 / A0); a2 = (float) (A2 / A0);
    }
    public void reset() { l1 = l2 = r1 = r2 = 0; }
    public void run(float[] l, float[] r, int off, int n) {
        float s1 = l1, s2 = l2, t1 = r1, t2 = r2;
        for (int i = off, e = off + n; i < e; i++) {
            float x = l[i], y = b0 * x + s1;
            s1 = b1 * x - a1 * y + s2; s2 = b2 * x - a2 * y; l[i] = y;
            x = r[i]; y = b0 * x + t1;
            t1 = b1 * x - a1 * y + t2; t2 = b2 * x - a2 * y; r[i] = y;
        }
        l1 = Effect.clean(s1); l2 = Effect.clean(s2); r1 = Effect.clean(t1); r2 = Effect.clean(t2);
    }
    /** Один канал (для синтезатора). */
    public float tick(float x) {
        float y = b0 * x + l1;
        l1 = b1 * x - a1 * y + l2; l2 = b2 * x - a2 * y;
        return y;
    }
}
