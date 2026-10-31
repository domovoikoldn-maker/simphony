package com.domovoi4ik.symphony.synth;

/**
 * Синтезированная ударная установка. Раскладка General MIDI (36 — бочка, 38 — малый, 42 — закрытый хэт…),
 * поэтому подходит к любой MIDI-клавиатуре с пэдами. Вне диапазона GM — по нотам внутри октавы.
 */
public final class Drums {
    static final int KICK = 0, RIM = 1, SNARE = 2, CLAP = 3, TOM_LO = 4, HAT_CLOSED = 5, TOM_MID = 6, HAT_PEDAL = 7,
        TOM_HI = 8, HAT_OPEN = 9, CRASH = 10, RIDE = 11, COWBELL = 12;
    public static final String[] NAMES = {"Бочка", "Римшот", "Малый", "Хлопок", "Том низкий", "Хэт закр.", "Том средний",
        "Хэт педаль", "Том высокий", "Хэт откр.", "Крэш", "Райд", "Ковбелл"};
    /** Октава: до, до♯, ре, ре♯, ми, фа, фа♯, соль, соль♯, ля, ля♯, си. */
    static final int[] OCTAVE = {KICK, RIM, SNARE, CLAP, SNARE, TOM_LO, HAT_CLOSED, TOM_MID, HAT_PEDAL, TOM_HI, HAT_OPEN, CRASH};

    private Drums() { }

    static int kind(int pitch) {
        switch (pitch) {
            case 35: case 36: return KICK;
            case 37: return RIM;
            case 38: case 40: return SNARE;
            case 39: return CLAP;
            case 41: case 43: return TOM_LO;
            case 42: case 54: return HAT_CLOSED;
            case 44: return HAT_PEDAL;
            case 45: case 47: return TOM_MID;
            case 46: return HAT_OPEN;
            case 48: case 50: return TOM_HI;
            case 49: case 52: case 55: case 57: return CRASH;
            case 51: case 53: case 59: return RIDE;
            case 56: return COWBELL;
            default: return OCTAVE[((pitch % 12) + 12) % 12];
        }
    }
    /** Подпись клавиши на экранной клавиатуре. */
    public static String label(int pitch) { return NAMES[kind(pitch)]; }

    static void render(Synth.Voice v, float[] l, float[] r, int off, int n, Synth s) {
        final float dt = 1f / Synth.SR;
        for (int i = off, e = off + n; i < e; i++) {
            float t = v.t, y;
            switch (v.drum) {
                case KICK: {
                    float f = 48 + 110 * (float) Math.exp(-t * 28);
                    v.ph[0] += f / Synth.SR; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    y = (float) Math.sin(2 * Math.PI * v.ph[0]) * (float) Math.exp(-t * 6.5) * 1.6f;
                    if (t < 0.004f) y += s.noise() * 0.5f * (1 - t / 0.004f);
                    y = (float) Math.tanh(y * 1.2f);
                    if (t > 0.7f) v.active = false;
                    break;
                }
                case SNARE: {
                    v.ph[0] += 185f / Synth.SR; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    float tone = (float) Math.sin(2 * Math.PI * v.ph[0]) * (float) Math.exp(-t * 22);
                    float nz = hp(v, s.noise(), 0.7f) * (float) Math.exp(-t * 13);
                    y = tone * 0.6f + nz * 0.9f;
                    if (t > 0.45f) v.active = false;
                    break;
                }
                case CLAP: {
                    float burst = t < 0.03f ? ((int) (t / 0.0095f) % 2 == 0 ? 1 : 0.3f) : (float) Math.exp(-(t - 0.03f) * 16);
                    y = bp(v, s.noise(), 0.16f) * burst * 1.4f;
                    if (t > 0.4f) v.active = false;
                    break;
                }
                case RIM: {
                    v.ph[0] += 1700f / Synth.SR; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    y = ((float) Math.sin(2 * Math.PI * v.ph[0]) * 0.7f + s.noise() * 0.3f) * (float) Math.exp(-t * 70);
                    if (t > 0.12f) v.active = false;
                    break;
                }
                case TOM_LO: case TOM_MID: case TOM_HI: {
                    float base = v.drum == TOM_LO ? 90 : (v.drum == TOM_MID ? 130 : 180);
                    float f = base * (1 + 0.6f * (float) Math.exp(-t * 18));
                    v.ph[0] += f / Synth.SR; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    y = (float) Math.sin(2 * Math.PI * v.ph[0]) * (float) Math.exp(-t * 7) * 1.1f + s.noise() * 0.1f * (float) Math.exp(-t * 40);
                    if (t > 0.8f) v.active = false;
                    break;
                }
                case HAT_CLOSED: case HAT_PEDAL: case HAT_OPEN: case CRASH: case RIDE: {
                    float decay = v.drum == HAT_CLOSED ? 45 : (v.drum == HAT_PEDAL ? 30 : (v.drum == HAT_OPEN ? 6 : (v.drum == CRASH ? 2.2f : 3.5f)));
                    // «металл»: сумма шести меандров нечётных частот + шум, через ВЧ-фильтр
                    float m = 0;
                    float[] fr = {205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f};
                    for (int k = 0; k < 6; k++) { v.ph[k] += fr[k] * (v.drum == RIDE ? 1.6f : 2.1f) / Synth.SR; if (v.ph[k] >= 1) v.ph[k] -= 1; m += v.ph[k] < 0.5f ? 1 : -1; }
                    float src = m * 0.12f + s.noise() * (v.drum == CRASH ? 0.7f : 0.45f);
                    y = hp(v, src, v.drum == RIDE ? 0.55f : 0.82f) * (float) Math.exp(-t * decay) * (v.drum == RIDE ? 0.6f : 0.75f);
                    if (t * decay > 9) v.active = false;
                    break;
                }
                default: { // COWBELL
                    v.ph[0] += 540f / Synth.SR; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    v.ph[1] += 800f / Synth.SR; if (v.ph[1] >= 1) v.ph[1] -= 1;
                    y = bp(v, (v.ph[0] < 0.5f ? 0.5f : -0.5f) + (v.ph[1] < 0.5f ? 0.5f : -0.5f), 0.09f) * (float) Math.exp(-t * 9) * 1.5f;
                    if (t > 0.5f) v.active = false;
                    break;
                }
            }
            if (v.released) {   // заглушили (закрытый хэт поверх открытого)
                v.relT += dt;
                if (v.relLen < 1) { y *= (float) Math.exp(-v.relT * 6.9 / v.relLen); if (v.relT > v.relLen) v.active = false; }
            }
            float out = y * v.vel * 0.5f;
            l[i] += out; r[i] += out;
            v.t += dt;
            if (!v.active) return;
        }
    }

    /** Простой однополюсный ВЧ-фильтр на состоянии голоса. a — 0…1 (больше — выше срез). */
    static float hp(Synth.Voice v, float x, float a) {
        float lp = v.s1 + (x - v.s1) * (1 - a);
        v.s1 = lp;
        return x - lp;
    }
    /** Полосовой: SVF с фиксированной добротностью; g — относительная частота. */
    static float bp(Synth.Voice v, float x, float g) {
        float k = 0.5f;
        float hpv = (x - (k + g) * v.s1 - v.s2) / (1 + g * (g + k));
        float b = g * hpv + v.s1; v.s1 = g * hpv + b;
        float lp = g * b + v.s2; v.s2 = g * b + lp;
        return b;
    }
}
