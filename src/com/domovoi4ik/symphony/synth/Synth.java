/*
 * «Симфония» — встроенные инструменты. Разработчик и владелец: domovoi4ik.
 *
 * Synth — полифонический синтезатор одной инструментальной дорожки (16 голосов).
 * Всё считается на Java, без сэмплов (APK остаётся крошечным):
 *   • пианино, колокольчики, орган — аддитивный синтез (сумма обертонов со своим затуханием);
 *   • электропиано — FM-синтез;
 *   • лид, пэд, бас, струнные, щипок — «аналоговый» субтрактивный синтез (пилы/меандр → фильтр);
 *   • ударные — синтез бочки, малого, тарелок и т. д. (раскладка General MIDI).
 * Методы вызываются из звукового потока (render) и из движка (noteOn/Off) — тоже в звуковом потоке.
 */
package com.domovoi4ik.symphony.synth;

public final class Synth {
    public static final int SR = 44100, VOICES = 16;
    public static final int PIANO = 0, EPIANO = 1, LEAD = 2, PAD = 3, BASS = 4, ORGAN = 5, STRINGS = 6, PLUCK = 7, BELLS = 8, DRUMS = 9;
    public static final String[] NAMES = {"Пианино", "Электропиано", "Синт-лид", "Пэд", "Бас", "Орган", "Струнные", "Щипок", "Колокольчики", "Ударные"};

    // ---- субтрактивные пресеты: {осц.: 0 пила/1 меандр, голосов, расстройка (центы), саб-осц.,
    //      атака, спад, сустейн, затухание (с), срез фильтра (Гц), огибающая фильтра (Гц), резонанс, спад фильтра (с)}
    static final float[][] SUB = new float[10][];
    static {
        SUB[LEAD] = new float[]{0, 2, 9, 0, 0.004f, 0.25f, 0.75f, 0.18f, 1400, 3500, 0.35f, 0.25f};
        SUB[PAD] = new float[]{0, 3, 14, 0, 0.6f, 1.2f, 0.8f, 1.2f, 900, 1200, 0.15f, 1.5f};
        SUB[BASS] = new float[]{0, 1, 0, 0.6f, 0.002f, 0.35f, 0.55f, 0.08f, 180, 1600, 0.45f, 0.18f};
        SUB[STRINGS] = new float[]{0, 3, 11, 0, 0.18f, 0.6f, 0.85f, 0.45f, 2600, 1500, 0.1f, 0.8f};
        SUB[PLUCK] = new float[]{1, 2, 6, 0, 0.001f, 0.5f, 0.0f, 0.25f, 300, 5200, 0.3f, 0.22f};
    }

    private final Voice[] v = new Voice[VOICES];
    private int preset;
    private long age;
    private int rnd = 0x2545F491;

    public Synth(int preset) {
        for (int i = 0; i < VOICES; i++) v[i] = new Voice();
        this.preset = clampPreset(preset);
    }
    static int clampPreset(int p) { return p < 0 || p >= NAMES.length ? PIANO : p; }
    public int preset() { return preset; }
    public void setPreset(int p) { p = clampPreset(p); if (p != preset) { panic(); preset = p; } }

    public void noteOn(int pitch, int vel) {
        if (pitch < 0 || pitch > 127) return;
        vel = Math.max(1, Math.min(127, vel));
        if (preset == DRUMS) {
            int kind = Drums.kind(pitch);
            if (kind == Drums.HAT_CLOSED || kind == Drums.HAT_PEDAL)          // закрытый хэт глушит открытый
                for (Voice x : v) if (x.active && x.drum == Drums.HAT_OPEN) x.choke();
        }
        for (Voice x : v) if (x.active && x.note == pitch && !x.released) x.release(preset);   // повтор той же ноты
        Voice best = null;
        for (Voice x : v) if (!x.active) { best = x; break; }
        if (best == null) {                                   // кража: самый старый, лучше уже отпущенный
            for (Voice x : v) if (x.released && (best == null || x.born < best.born)) best = x;
            if (best == null) for (Voice x : v) if (best == null || x.born < best.born) best = x;
        }
        best.start(preset, pitch, vel / 127f, ++age, this);
    }
    public void noteOff(int pitch) { for (Voice x : v) if (x.active && x.note == pitch && !x.released) x.release(preset); }
    public void allOff() { for (Voice x : v) if (x.active && !x.released) x.release(preset); }
    public void panic() { for (Voice x : v) x.active = false; }
    public boolean busy() { for (Voice x : v) if (x.active) return true; return false; }
    public int activeVoices() { int n = 0; for (Voice x : v) if (x.active) n++; return n; }

    /** Прибавить звучание в l/r (моно-синтез, одинаково в оба канала; панораму делает дорожка). */
    public void render(float[] l, float[] r, int off, int n) {
        for (Voice x : v) if (x.active) x.render(preset, l, r, off, n, this);
    }

    float noise() { rnd ^= rnd << 13; rnd ^= rnd >>> 17; rnd ^= rnd << 5; return rnd * (1f / 2147483648f); }

    static float mtof(float m) { return (float) (440 * Math.pow(2, (m - 69) / 12.0)); }

    /** Пила без ступенек-алиасов (polyBLEP). t — фаза 0…1, dt — шаг. */
    static float saw(float t, float dt) {
        float y = 2 * t - 1;
        if (t < dt) { float x = t / dt; y -= x + x - x * x - 1; }
        else if (t > 1 - dt) { float x = (t - 1) / dt; y -= x * x + x + x + 1; }
        return y;
    }
    static float square(float t, float dt) {
        float y = t < 0.5f ? 1 : -1;
        y += blep(t, dt);
        float t2 = t + 0.5f; if (t2 >= 1) t2 -= 1;
        y -= blep(t2, dt);
        return y;
    }
    static float blep(float t, float dt) {
        if (t < dt) { float x = t / dt; return x + x - x * x - 1; }
        if (t > 1 - dt) { float x = (t - 1) / dt; return x * x + x + x + 1; }
        return 0;
    }

    // ================================================================== голос
    static final class Voice {
        boolean active, released;
        int note, drum;
        long born;
        float vel, freq, t;            // t — время звучания, с
        // огибающая
        float env, relFrom, relT, relLen;
        // осцилляторы
        final float[] ph = new float[8];
        final float[] det = new float[4];
        // фильтр (TPT SVF)
        float s1, s2, g, k;
        // аддитив
        final float[] amp = new float[8], dec = new float[8], ratio = new float[8];
        int partials;
        float hammer;

        void start(int preset, int pitch, float velocity, long born, Synth s) {
            active = true; released = false; note = pitch; vel = velocity; this.born = born;
            freq = mtof(pitch); t = 0; env = 0; relT = 0; s1 = s2 = 0; hammer = 1;
            for (int i = 0; i < ph.length; i++) ph[i] = (s.noise() + 1) * 0.5f;   // случайные фазы — живее
            if (preset == DRUMS) { drum = Drums.kind(pitch); ph[0] = 0; return; }
            drum = -1;
            float[] sp = SUB[preset];
            if (sp != null) {
                int n = (int) sp[1];
                for (int i = 0; i < n; i++) det[i] = (float) Math.pow(2, (i - (n - 1) / 2f) * sp[2] / 1200f / Math.max(1, (n - 1) / 2f));
            } else setupAdditive(preset, pitch);
        }

        void setupAdditive(int preset, int pitch) {
            float keyDecay = (float) Math.pow(2, (pitch - 60) / 24.0);    // выше — короче
            if (preset == PIANO) {
                partials = 8;
                for (int i = 0; i < 8; i++) {
                    int n = i + 1;
                    ratio[i] = n * (float) Math.sqrt(1 + 0.0004 * n * n);
                    amp[i] = (float) (1 / Math.pow(n, 1.3)) * (0.6f + 0.4f * vel) * (n > 3 ? vel : 1);
                    dec[i] = 0.55f * keyDecay * (1 + 0.55f * i);
                }
            } else if (preset == ORGAN) {
                partials = 6;
                float[] r = {0.5f, 1, 2, 3, 4, 6}, a = {0.5f, 1, 0.7f, 0.35f, 0.3f, 0.15f};
                for (int i = 0; i < 6; i++) { ratio[i] = r[i]; amp[i] = a[i] * 0.55f; dec[i] = 0; }
            } else if (preset == BELLS) {
                partials = 5;
                float[] r = {1, 2.76f, 5.4f, 8.93f, 13.34f}, a = {1, 0.6f, 0.35f, 0.22f, 0.12f};
                for (int i = 0; i < 5; i++) { ratio[i] = r[i]; amp[i] = a[i] * 0.7f; dec[i] = (0.35f + 0.9f * i) * keyDecay; }
            } else { // EPIANO — FM
                partials = 0;
            }
        }

        void release(int preset) {
            released = true; relFrom = env; relT = 0;
            if (preset == DRUMS) { relLen = 10; return; }           // ударные звучат до конца
            float[] sp = SUB[preset];
            relLen = sp != null ? sp[7] : (preset == PIANO ? 0.25f : (preset == ORGAN ? 0.06f : (preset == BELLS ? 1.6f : 0.35f)));
        }
        void choke() { released = true; relFrom = env; relT = 0; relLen = 0.02f; }

        void render(int preset, float[] l, float[] r, int off, int n, Synth s) {
            final float dt = 1f / SR;
            if (preset == DRUMS) { Drums.render(this, l, r, off, n, s); return; }
            float[] sp = SUB[preset];
            for (int i = off, e = off + n; i < e; i++) {
                float out;
                if (sp != null) out = subtractive(sp, i, s);
                else if (preset == EPIANO) out = epiano();
                else out = additive(preset, s);
                // общая огибающая отпускания
                if (released) {
                    relT += dt;
                    float rel = relLen <= 0 ? 0 : (float) Math.exp(-relT * 6.9 / relLen) * relFrom;
                    if (sp == null) out *= rel / Math.max(1e-4f, relFrom); else env = rel;
                    if (relT > relLen) { active = false; return; }
                }
                float y = out * vel * 0.32f;
                l[i] += y; r[i] += y;
                t += dt;
            }
            if (!released && sp == null && preset != ORGAN && t > 12) active = false;
        }

        float additive(int preset, Synth s) {
            float y = 0, sum = 0;
            for (int p = 0; p < partials; p++) {
                float a = dec[p] > 0 ? amp[p] * (float) Math.exp(-t * dec[p]) : amp[p];
                y += a * (float) Math.sin(2 * Math.PI * ph[p]);
                sum += a;
                ph[p] += freq * ratio[p] / SR; if (ph[p] >= 1) ph[p] -= 1;
            }
            float att = Math.min(1, t / (preset == ORGAN ? 0.006f : 0.0015f));
            if (preset == PIANO && t < 0.02f) y += s.noise() * 0.15f * (1 - t / 0.02f) * vel;   // молоточек
            if (!released) env = att * Math.min(1, sum + 0.01f);
            return y * att * 0.55f;
        }

        float epiano() {
            float a = (float) Math.exp(-t * 1.3) * Math.min(1, t / 0.001f);
            float idx = (1.2f + 2.5f * vel) * (float) Math.exp(-t * 4);
            float mod = (float) Math.sin(2 * Math.PI * ph[1]);
            float tine = (float) Math.sin(2 * Math.PI * ph[2]) * (float) Math.exp(-t * 18) * 0.25f * vel;
            float y = (float) Math.sin(2 * Math.PI * ph[0] + idx * mod) * a + tine;
            ph[0] += freq / SR; if (ph[0] >= 1) ph[0] -= 1;
            ph[1] += freq / SR; if (ph[1] >= 1) ph[1] -= 1;
            ph[2] += freq * 14 / SR; if (ph[2] >= 1) ph[2] -= 1;
            if (!released) env = a;
            if (!released && t > 6) { released = true; relFrom = env; relT = 0; relLen = 0.3f; }
            return y;
        }

        float subtractive(float[] sp, int i, Synth s) {
            // амплитудная ADSR
            float A = sp[4], D = sp[5], S = sp[6];
            if (!released) env = t < A ? t / A : S + (1 - S) * (float) Math.exp(-(t - A) * 5 / D);
            int nOsc = (int) sp[1];
            float y = 0;
            for (int o = 0; o < nOsc; o++) {
                float inc = freq * det[o] / SR;
                y += sp[0] == 0 ? saw(ph[o], inc) : square(ph[o], inc);
                ph[o] += inc; if (ph[o] >= 1) ph[o] -= 1;
            }
            y /= (float) Math.sqrt(nOsc);
            if (sp[3] > 0) { float inc = freq * 0.5f / SR; y += sp[3] * square(ph[5], inc); ph[5] += inc; if (ph[5] >= 1) ph[5] -= 1; }
            // фильтр: срез + огибающая, пересчёт коэффициентов раз в 16 сэмплов
            if ((i & 15) == 0 || g == 0) {
                float fc = sp[8] + sp[9] * (float) Math.exp(-t / sp[11]) * (0.4f + 0.6f * vel);
                fc = Math.min(fc, SR * 0.45f);
                g = (float) Math.tan(Math.PI * fc / SR);
                k = 2 - 1.9f * sp[10];
            }
            float hp = (y - (k + g) * s1 - s2) / (1 + g * (g + k));
            float bp = g * hp + s1; s1 = g * hp + bp;
            float lp = g * bp + s2; s2 = g * bp + lp;
            s1 = clean(s1); s2 = clean(s2);
            return lp * env;
        }
        static float clean(float x) { return x != x || Math.abs(x) > 1e4f ? 0 : x; }
    }
}
