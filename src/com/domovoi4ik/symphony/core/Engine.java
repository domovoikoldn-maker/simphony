/*
 * «Симфония» — звуковой движок. Разработчик и владелец: domovoi4ik.
 *
 * Engine сводит проект в стерео: аудиодорожки (WAV) + инструменты (ноты → синтезатор),
 * на каждой дорожке — её цепочка эффектов, громкость и панорама, затем мастер-цепочка и лимитер.
 * Один и тот же код играет в реальном времени (Output → AudioTrack) и считает экспорт —
 * что слышно, то и окажется в файле.
 *
 * Потоки: render() вызывает звуковой поток; управление (play/stop/seek/ноты с клавиатуры)
 * приходит из интерфейса. Все публичные методы synchronized — блок длится ~5 мс, интерфейс не тормозит.
 * Ноты с экранной/MIDI-клавиатуры складываются в очередь и применяются в начале блока.
 */
package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.audio.TrackReader;
import com.domovoi4ik.symphony.dsp.Effect;
import com.domovoi4ik.symphony.dsp.Effects;
import com.domovoi4ik.symphony.dsp.FxChain;
import com.domovoi4ik.symphony.synth.Synth;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;

public final class Engine {
    public static final int CHUNK = 64;           // точность событий: 64 сэмпла ≈ 1,5 мс
    final Project p;
    final File dir;
    final boolean offline;                        // экспорт: свои копии эффектов, без метронома
    private final IdentityHashMap<Track, TS> state = new IdentityHashMap<Track, TS>();
    private float[] mixL = new float[0], mixR = new float[0];

    private boolean playing;
    private long frame;                           // позиция транспорта (кадры от начала песни)
    private int excludeTrack = -1;                // эта дорожка сейчас пишется с микрофона — не играть
    private int tail;                             // сколько ещё досчитывать хвосты после остановки
    // запись нот
    private int recTrack = -1;
    private final int[] recStart = new int[128], recVel = new int[128];
    private final ArrayList<Note> recorded = new ArrayList<Note>();
    // очередь живых нот: trackId, pitch, vel (0 = отпускание)
    private int[] queue = new int[3 * 256];
    private int queued;
    // метроном
    private float clickPh, clickEnv, clickFreq;
    private String lastError;

    /** Состояние дорожки внутри движка. */
    static final class TS {
        Synth synth;
        TrackReader reader;
        String readerFile;
        Note[] notesRef;
        int next;                                   // индекс следующей ноты для включения
        final int[] offPitch = new int[128];
        final long[] offFrame = new long[128];
        int offs;
        float[] l = new float[0], r = new float[0], mono = new float[0];
        FxChain fx;                                 // offline: копия цепочки
        volatile float peak;
    }

    public Engine(Project p, File dir, boolean offline) { this.p = p; this.dir = dir; this.offline = offline; }

    // ================================================================== управление
    public synchronized void play(long from) {
        frame = Math.max(0, from);
        playing = true;
        tail = 0;
        for (Track t : p.tracks()) {
            TS s = ts(t);
            if (s.synth != null) s.synth.allOff();
            s.offs = 0;
            s.notesRef = null;            // пересинхронизация нот в render
            openReader(t, s);
        }
        if (offline) { for (Track t : p.tracks()) ts(t).fx.reset(); master().reset(); }
    }
    public synchronized void stop() {
        if (!playing) return;
        playing = false;
        for (Track t : p.tracks()) {
            TS s = state.get(t);
            if (s == null) continue;
            if (s.synth != null) { for (int i = 0; i < s.offs; i++) s.synth.noteOff(s.offPitch[i]); s.offs = 0; }
            closeReader(s);
        }
        finishMidiRecord();
        tail = Project.SR * 3;
    }
    public synchronized void seek(long to) { boolean was = playing; if (was) stop(); frame = Math.max(0, to); if (was) play(frame); }
    public synchronized boolean playing() { return playing; }
    public synchronized long position() { return frame; }
    public synchronized void exclude(int trackId) { excludeTrack = trackId; }
    /** Тишина немедленно (смена инструмента, уход в фон). */
    public synchronized void panic() {
        for (TS s : state.values()) { if (s.synth != null) s.synth.panic(); s.offs = 0; }
        for (Track t : p.tracks()) t.fx.reset();
        p.master.reset();
        queued = 0;
    }
    /** Есть ли ещё что считать: транспорт, звучащие голоса, хвосты эффектов. */
    public synchronized boolean busy() {
        if (playing || queued > 0 || tail > 0) return true;
        for (TS s : state.values()) if (s.synth != null && s.synth.busy()) return true;
        return false;
    }
    public String takeError() { String e = lastError; lastError = null; return e; }
    public float peak(Track t) { TS s = state.get(t); return s == null ? 0 : s.peak; }

    // ---- живые ноты (экранная и MIDI-клавиатура) ----
    public synchronized void noteOn(int trackId, int pitch, int vel) { enqueue(trackId, pitch, Math.max(1, vel)); }
    public synchronized void noteOff(int trackId, int pitch) { enqueue(trackId, pitch, 0); }
    private void enqueue(int id, int pitch, int vel) {
        if (queued * 3 + 3 > queue.length) return;      // переполнение — лишнее отбросим
        queue[queued * 3] = id; queue[queued * 3 + 1] = pitch; queue[queued * 3 + 2] = vel;
        queued++;
    }

    // ---- запись нот с клавиатуры ----
    public synchronized void startMidiRecord(int trackId) {
        recTrack = trackId;
        recorded.clear();
        java.util.Arrays.fill(recStart, -1);
    }
    /** Остановить запись нот и вернуть сыгранное (тики от начала песни). */
    public synchronized ArrayList<Note> stopMidiRecord() {
        finishMidiRecord();
        recTrack = -1;
        ArrayList<Note> r = new ArrayList<Note>(recorded);
        recorded.clear();
        return r;
    }
    public synchronized int midiRecordTrack() { return recTrack; }
    private void finishMidiRecord() {
        if (recTrack < 0) return;
        long now = p.frameToTick(frame);
        for (int k = 0; k < 128; k++) if (recStart[k] >= 0) {
            recorded.add(new Note(recStart[k], (int) Math.max(1, now - recStart[k]), k, recVel[k]));
            recStart[k] = -1;
        }
    }

    // ================================================================== расчёт звука
    /** Посчитать n кадров стерео в l/r (значения −1…1 после лимитера). */
    public synchronized void render(float[] l, float[] r, int n) {
        if (mixL.length < n) { mixL = new float[n]; mixR = new float[n]; }
        java.util.Arrays.fill(l, 0, n, 0f);
        java.util.Arrays.fill(r, 0, n, 0f);
        applyQueue();
        Track[] tracks = p.tracks();
        boolean anySolo = false;
        for (Track t : tracks) if (t.solo) anySolo = true;
        float bpm = p.bpm;
        for (Track t : tracks) {
            TS s = ts(t);
            if (s.l.length < n) { s.l = new float[n]; s.r = new float[n]; s.mono = new float[n]; }
            java.util.Arrays.fill(s.l, 0, n, 0f);
            java.util.Arrays.fill(s.r, 0, n, 0f);
            boolean source = false;
            if (t.type == Track.INSTRUMENT) {
                s.synth.setPreset(t.instrument);
                for (int c = 0; c < n; c += CHUNK) {
                    int m = Math.min(CHUNK, n - c);
                    if (playing) schedule(t, s, frame + c, m);
                    s.synth.render(s.l, s.r, c, m);
                }
                source = true;
            } else if (playing && t.id != excludeTrack && s.reader != null) {
                long local = frame - t.offset;
                if (local + n > 0) {
                    int skip = (int) Math.max(0, -local);
                    try {
                        s.reader.readMono(s.mono, skip, n - skip);
                        for (int i = 0; i < skip; i++) s.mono[i] = 0;
                        for (int i = 0; i < n; i++) { s.l[i] = s.mono[i]; s.r[i] = s.mono[i]; }
                        source = true;
                    } catch (IOException e) {
                        lastError = "Не удалось прочитать «" + t.name + "»: " + e.getMessage();
                        closeReader(s);
                    }
                }
            }
            FxChain fx = offline ? s.fx : t.fx;
            fx.bpm = bpm;
            if (source || fx.active()) fx.process(s.l, s.r, 0, n);
            boolean audible = !t.mute && (!anySolo || t.solo);
            float gl = t.gainL(), gr = t.gainR(), pk = 0;
            if (audible) {
                for (int i = 0; i < n; i++) {
                    float a = s.l[i] * gl, b = s.r[i] * gr;
                    l[i] += a; r[i] += b;
                    float m = Math.max(Math.abs(a), Math.abs(b)); if (m > pk) pk = m;
                }
            }
            s.peak = pk;
        }
        if (playing && p.metronome && !offline) metronome(l, r, n);
        FxChain m = master();
        m.bpm = bpm;
        m.process(l, r, 0, n);
        float mv = Track.gainOf(p.masterVolume);
        for (int i = 0; i < n; i++) { l[i] = Mix.softClip(l[i] * mv); r[i] = Mix.softClip(r[i] * mv); }
        if (playing) frame += n; else if (tail > 0) tail -= n;
    }

    private FxChain masterCopy;
    private FxChain master() {
        if (!offline) return p.master;
        if (masterCopy == null) masterCopy = copy(p.master);
        return masterCopy;
    }

    /** Включить/выключить ноты, попадающие в [f, f+m). */
    private void schedule(Track t, TS s, long f, int m) {
        Note[] notes = t.notes();
        long end = f + m;
        if (notes != s.notesRef) {          // ноты отредактированы (или старт) — найти место
            s.notesRef = notes;
            long tick = p.frameToTick(f);
            int lo = 0, hi = notes.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (notes[mid].start < tick) lo = mid + 1; else hi = mid; }
            s.next = lo;
        }
        // сначала отпускания (чтобы повтор той же ноты не съелся)
        for (int i = 0; i < s.offs; ) {
            if (s.offFrame[i] < end) {
                s.synth.noteOff(s.offPitch[i]);
                s.offs--; s.offPitch[i] = s.offPitch[s.offs]; s.offFrame[i] = s.offFrame[s.offs];
            } else i++;
        }
        while (s.next < notes.length) {
            Note nt = notes[s.next];
            long nf = p.tickToFrame(nt.start);
            if (nf >= end) break;
            s.next++;
            if (nf < f - Project.SR / 10) continue;                // далеко позади (после перемотки) — пропустить
            s.synth.noteOn(nt.pitch, nt.vel);
            if (s.offs < s.offPitch.length) { s.offPitch[s.offs] = nt.pitch; s.offFrame[s.offs] = p.tickToFrame(nt.end()); s.offs++; }
        }
    }

    private void applyQueue() {
        if (queued == 0) return;
        for (int q = 0; q < queued; q++) {
            int id = queue[q * 3], pitch = queue[q * 3 + 1], vel = queue[q * 3 + 2];
            Track t = p.byId(id);
            if (t == null || t.type != Track.INSTRUMENT) continue;
            Synth sy = ts(t).synth;
            sy.setPreset(t.instrument);
            if (vel > 0) sy.noteOn(pitch, vel); else sy.noteOff(pitch);
            if (recTrack == id && playing && pitch >= 0 && pitch < 128) {
                int tick = (int) p.frameToTick(frame);
                if (vel > 0) {
                    if (recStart[pitch] >= 0) recorded.add(new Note(recStart[pitch], Math.max(1, tick - recStart[pitch]), pitch, recVel[pitch]));
                    recStart[pitch] = tick; recVel[pitch] = vel;
                } else if (recStart[pitch] >= 0) {
                    recorded.add(new Note(recStart[pitch], Math.max(1, tick - recStart[pitch]), pitch, recVel[pitch]));
                    recStart[pitch] = -1;
                }
            }
        }
        queued = 0;
    }

    /** Щелчок на каждую долю, на первую долю такта — выше. */
    private void metronome(float[] l, float[] r, int n) {
        long fpb = p.tickToFrame(Project.PPQ);
        if (fpb <= 0) return;
        for (int i = 0; i < n; i++) {
            long f = frame + i;
            if (f % fpb == 0) { long beat = f / fpb; clickFreq = beat % p.beats == 0 ? 1760 : 1175; clickEnv = 0.5f; clickPh = 0; }
            if (clickEnv > 0.001f) {
                float y = (float) Math.sin(2 * Math.PI * clickPh) * clickEnv;
                clickPh += clickFreq / Project.SR; if (clickPh >= 1) clickPh -= 1;
                clickEnv *= 0.9985f;
                l[i] += y; r[i] += y;
            }
        }
    }

    // ================================================================== вспомогательное
    private TS ts(Track t) {
        TS s = state.get(t);
        if (s == null) {
            s = new TS();
            if (t.type == Track.INSTRUMENT) s.synth = new Synth(t.instrument);
            s.fx = offline ? copy(t.fx) : t.fx;
            state.put(t, s);
        }
        return s;
    }
    private void openReader(Track t, TS s) {
        if (t.type != Track.AUDIO) return;
        closeReader(s);
        if (t.file == null || t.frames <= 0) return;
        try {
            s.reader = new TrackReader(new File(dir, t.file), Math.max(0, frame - t.offset));
            s.readerFile = t.file;
        } catch (IOException e) {
            lastError = "Дорожка «" + t.name + "» повреждена: " + e.getMessage();
        }
    }
    private static void closeReader(TS s) { if (s.reader != null) { s.reader.close(); s.reader = null; } }
    /** Забыть дорожки, которых больше нет в проекте. */
    public synchronized void sync() {
        java.util.Iterator<java.util.Map.Entry<Track, TS>> it = state.entrySet().iterator();
        Track[] tr = p.tracks();
        while (it.hasNext()) {
            java.util.Map.Entry<Track, TS> e = it.next();
            boolean found = false;
            for (Track t : tr) if (t == e.getKey()) found = true;
            if (!found) { closeReader(e.getValue()); it.remove(); }
        }
    }
    public synchronized void close() { for (TS s : state.values()) closeReader(s); state.clear(); }

    /** Копия цепочки эффектов с теми же настройками (для экспорта параллельно с живым звуком). */
    public static FxChain copy(FxChain src) {
        FxChain c = new FxChain();
        for (Effect e : src.list()) {
            Effect x = Effects.create(e.type);
            if (x == null) continue;
            System.arraycopy(e.p, 0, x.p, 0, Math.min(e.p.length, x.p.length));
            x.on = e.on;
            c.add(x);
        }
        return c;
    }
}
