package com.domovoi4ik.symphony.core;

/** Общие функции сведения. */
public final class Mix {
    private Mix() { }
    /** До 0,8 — без изменений, выше — плавно прижимаем к 1,0 (вместо жёсткого клиппинга). */
    public static float softClip(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float y = 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f);
        return x < 0 ? -y : y;
    }
    public static short toShort(float x) {
        if (x != x) return 0;
        int v = Math.round(x * 32767f);
        return (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
    }
    public static String time(long frames) {
        long ds = Math.max(0, frames) * 10 / Project.SR;
        return String.format(java.util.Locale.US, "%02d:%02d.%d", ds / 600, ds / 10 % 60, ds % 10);
    }
    /** «такт.доля» (с 1). */
    public static String barBeat(Project p, long frame) {
        long tick = p.frameToTick(frame);
        long beat = tick / Project.PPQ;
        return (beat / p.beats + 1) + "." + (beat % p.beats + 1);
    }
}
