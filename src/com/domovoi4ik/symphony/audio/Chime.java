package com.domovoi4ik.symphony.audio;

/** Звуковая метка бесплатного экспорта: пауза 0,4 с и три тихие ноты (до–ми–соль) с затуханием. */
public final class Chime {
    private Chime() { }
    public static short[] make(int rate) {
        double[] notes = {1046.5, 1318.5, 1568.0};
        int gap = (int) (rate * 0.4), step = (int) (rate * 0.18), tail = (int) (rate * 0.8);
        int n = gap + step * (notes.length - 1) + tail;
        float[] acc = new float[n];
        for (int k = 0; k < notes.length; k++) {
            int start = gap + k * step;
            for (int i = 0; start + i < n && i < tail; i++) {
                double t = i / (double) rate;
                double env = Math.min(1, i / (rate * 0.005)) * Math.exp(-t * 5.5);
                acc[start + i] += (float) (0.12 * env * (Math.sin(2 * Math.PI * notes[k] * t) + 0.3 * Math.sin(4 * Math.PI * notes[k] * t)));
            }
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = com.domovoi4ik.symphony.core.Mix.toShort(acc[i]);
        return out;
    }
}
