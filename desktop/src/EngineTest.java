import com.domovoi4ik.symphony.audio.Wav;
import com.domovoi4ik.symphony.core.*;
import com.domovoi4ik.symphony.dsp.*;
import com.domovoi4ik.symphony.synth.Synth;

import java.io.*;
import java.util.ArrayList;
import java.util.Random;

/** Проверка ядра «Симфонии» 2.0 на ПК: синтезаторы, эффекты, движок, запись нот, проект, экспорт WAV/MIDI, скорость. */
public class EngineTest {
    static int fails;
    static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        File dir = new File(a.length > 0 ? a[0] : "build/test2");
        dir.mkdirs();

        // ---- 1. каждый инструмент звучит и затихает после отпускания
        for (int pr = 0; pr < Synth.NAMES.length; pr++) {
            Synth s = new Synth(pr);
            float[] l = new float[512], r = new float[512];
            int pitch = pr == Synth.DRUMS ? 38 : 60;
            s.noteOn(pitch, 100);
            float peak = 0; boolean bad = false;
            for (int b = 0; b < 86; b++) { java.util.Arrays.fill(l, 0); java.util.Arrays.fill(r, 0); s.render(l, r, 0, 512); for (float x : l) { peak = Math.max(peak, Math.abs(x)); if (x != x) bad = true; } }
            s.noteOff(pitch);
            for (int b = 0; b < 86 * 14 && s.busy(); b++) { java.util.Arrays.fill(l, 0); s.render(l, r, 0, 512); }
            check(String.format("инструмент %-13s пик %.2f, затих: %s", Synth.NAMES[pr], peak, !s.busy()), !bad && peak > 0.05f && peak < 1.5f && !s.busy());
        }
        // все ударные
        Synth dr = new Synth(Synth.DRUMS);
        boolean allDrums = true;
        for (int pitch = 35; pitch <= 59; pitch++) {
            dr.panic(); dr.noteOn(pitch, 110);
            float[] l = new float[4096], r = new float[4096];
            dr.render(l, r, 0, 4096);
            float pk = 0; for (float x : l) pk = Math.max(pk, Math.abs(x));
            if (!(pk > 0.02f && pk < 2)) { allDrums = false; System.out.println("     тихо/громко: нота " + pitch + " пик " + pk); }
        }
        check("ударные 35…59 (GM) все звучат", allDrums);

        // ---- 2. эффекты: крайние значения ручек на шуме — без NaN и «взрывов»
        Random rnd = new Random(1);
        for (int type = 0; type < Effects.NAMES.length; type++) {
            boolean ok = true; float peak = 0;
            for (int trial = 0; trial < 6; trial++) {
                Effect e = Effects.create(type);
                for (int i = 0; i < e.p.length; i++) e.p[i] = trial < 2 ? trial : rnd.nextFloat();
                if (e instanceof FxChain.Synced) ((FxChain.Synced) e).bpm(60 + rnd.nextInt(180));
                float[] l = new float[1024], r = new float[1024];
                for (int b = 0; b < 200; b++) {
                    for (int i = 0; i < 1024; i++) { l[i] = (rnd.nextFloat() * 2 - 1) * 0.5f; r[i] = (rnd.nextFloat() * 2 - 1) * 0.5f; }
                    e.process(l, r, 0, 1024);
                    for (int i = 0; i < 1024; i++) { if (l[i] != l[i] || r[i] != r[i]) ok = false; peak = Math.max(peak, Math.abs(l[i])); }
                }
                String[] pn = e.paramNames();
                for (int i = 0; i < pn.length; i++) if (e.format(i) == null) ok = false;
            }
            check(String.format("эффект %-20s крайние значения: без NaN, пик %.1f", Effects.NAMES[type], peak), ok && peak < 60);
        }

        // ---- 3. демо-проект
        File wav = new File(dir, "a1.wav");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(wav))) {
            int n = Project.SR * 4;
            o.write(Wav.header(n * 2L, Project.SR, 1));
            for (int i = 0; i < n; i++) { short s = Mix.toShort((float) (0.3 * Math.sin(2 * Math.PI * 220 * i / Project.SR) * Math.exp(-(i % 22050) / 6000.0))); o.write(s); o.write(s >> 8); }
        }
        Project p = new Project();
        p.bpm = 100;
        Track drums = p.add(Track.INSTRUMENT, "Ударные"); drums.instrument = Synth.DRUMS;
        Track bass = p.add(Track.INSTRUMENT, "Бас"); bass.instrument = Synth.BASS;
        Track keys = p.add(Track.INSTRUMENT, "Пианино"); keys.instrument = Synth.PIANO;
        Track lead = p.add(Track.INSTRUMENT, "Лид"); lead.instrument = Synth.LEAD; lead.pan = 30;
        Track pad = p.add(Track.INSTRUMENT, "Пэд"); pad.instrument = Synth.PAD; pad.pan = -30;
        Track ep = p.add(Track.INSTRUMENT, "Электропиано"); ep.instrument = Synth.EPIANO;
        Track voc = p.add(Track.AUDIO, "Голос"); voc.file = "a1.wav"; voc.frames = Wav.read(wav).frames(); voc.offset = p.tickToFrame(Project.PPQ * 4);
        ArrayList<Note> dn = new ArrayList<Note>(), bn = new ArrayList<Note>(), kn = new ArrayList<Note>(), ln = new ArrayList<Note>(), pn = new ArrayList<Note>();
        int Q = Project.PPQ;
        int[] chord = {0, 5, 7, 3}; // C F G Eb (условно)
        for (int bar = 0; bar < 8; bar++) {
            int b0 = bar * 4 * Q;
            for (int beat = 0; beat < 4; beat++) {
                dn.add(new Note(b0 + beat * Q, Q / 4, beat % 2 == 0 ? 36 : 38, 110));
                dn.add(new Note(b0 + beat * Q, Q / 4, 42, 70)); dn.add(new Note(b0 + beat * Q + Q / 2, Q / 4, 42, 60));
                bn.add(new Note(b0 + beat * Q, Q / 2, 36 + chord[bar % 4], 100));
            }
            int root = 60 + chord[bar % 4];
            for (int k : new int[]{0, 4, 7}) { kn.add(new Note(b0, Q * 2, root + k, 80)); pn.add(new Note(b0, Q * 4, root + k - 12, 70)); }
            for (int i = 0; i < 4; i++) ln.add(new Note(b0 + Q * 2 + i * Q / 2, Q / 2, root + 12 + new int[]{0, 2, 4, 7}[i], 90));
        }
        drums.setNotes(dn); bass.setNotes(bn); keys.setNotes(kn); lead.setNotes(ln); pad.setNotes(pn);
        ep.addNote(new Note(0, Q * 8, 72, 90));
        keys.fx.add(Effects.create(Effects.REVERB));
        lead.fx.add(Effects.create(Effects.DELAY)); lead.fx.add(Effects.create(Effects.REVERB));
        pad.fx.add(Effects.create(Effects.CHORUS)); pad.fx.add(Effects.create(Effects.FILTER));
        bass.fx.add(Effects.create(Effects.COMP)); bass.fx.add(Effects.create(Effects.DRIVE));
        drums.fx.add(Effects.create(Effects.EQ)); drums.fx.add(Effects.create(Effects.COMP));
        voc.fx.add(Effects.create(Effects.EQ)); voc.fx.add(Effects.create(Effects.REVERB));
        p.master.add(Effects.create(Effects.COMP)); p.master.add(Effects.create(Effects.WIDENER));
        ep.fx.add(Effects.create(Effects.TREMOLO)); ep.fx.add(Effects.create(Effects.BITCRUSH));

        long len = p.lengthFrames();
        check("длина песни = 8 тактов при 100 BPM (" + Mix.time(len) + ")", Math.abs(len - p.tickToFrame(32L * Q)) < 2);

        // ---- 4. экспорт WAV
        File out = new File(dir, "demo.wav");
        final int[] pct = {-1};
        long t0 = System.nanoTime();
        try (OutputStream o = new FileOutputStream(out)) {
            Exporter.export(p, dir, o, true, new Exporter.Progress() {
                public void onProgress(int v) { pct[0] = v; }
                public boolean cancelled() { return false; }
            });
        }
        double secs = (System.nanoTime() - t0) / 1e9, songSecs = (len + Exporter.TAIL) / (double) Project.SR;
        Wav.Info wi = Wav.read(out);
        check("экспорт: WAV стерео 44,1 кГц 16 бит", wi.channels == 2 && wi.rate == 44100 && wi.bits == 16);
        check("экспорт: длина = песня + 2 с хвоста + метка", wi.frames() == len + Exporter.TAIL + com.domovoi4ik.symphony.audio.Chime.make(44100).length);
        check("экспорт: прогресс 100%", pct[0] == 100);
        // анализ: нет тишины, нет клиппинга, левый ≠ правый (панорама/ширина)
        double[] st = stats(out, wi);
        check(String.format("сведение: RMS %.3f, пик %.3f, разница L/R %.3f", st[0], st[1], st[2]), st[0] > 0.03 && st[1] <= 1.0 && st[2] > 0.001);
        System.out.printf("     скорость: %.1f с песни за %.2f с (%.0f× быстрее реального времени, ПК)%n", songSecs, secs, songSecs / secs);

        // ---- 5. живой режим: блоки по 256, как на телефоне; запись нот с клавиатуры
        Engine e = new Engine(p, dir, false);
        float[] l = new float[256], r = new float[256];
        e.play(0);
        e.startMidiRecord(lead.id);
        int blocks = (int) (Project.SR * 2 / 256);
        long tr0 = System.nanoTime();
        for (int b = 0; b < blocks; b++) {
            if (b == 20) e.noteOn(lead.id, 64, 100);
            if (b == 60) e.noteOff(lead.id, 64);
            if (b == 100) e.noteOn(lead.id, 67, 90);
            e.render(l, r, 256);
        }
        double rt = (System.nanoTime() - tr0) / 1e9;
        ArrayList<Note> rec = e.stopMidiRecord();
        e.stop();
        check("запись с клавиатуры: 2 ноты (" + rec.size() + "), вторая дописана при остановке", rec.size() == 2);
        if (rec.size() == 2) {
            Note n0 = rec.get(0).pitch == 64 ? rec.get(0) : rec.get(1);
            long expectStart = p.frameToTick(20 * 256L), expectLen = p.frameToTick(60 * 256L) - expectStart;
            check("время записанной ноты точное (" + n0.start + "/" + expectStart + ", " + n0.len + "/" + expectLen + ")", Math.abs(n0.start - expectStart) <= 1 && Math.abs(n0.len - expectLen) <= 2);
        }
        System.out.printf("     живой режим 7 дорожек + 13 эффектов: %.0f%% нагрузки одного ядра ПК%n", rt / 2 * 100);
        // правка нот во время игры
        e.play(0);
        for (int b = 0; b < 50; b++) { if (b == 25) keys.addNote(new Note((int) p.frameToTick(40 * 256L), Q, 84, 100)); e.render(l, r, 256); }
        e.stop();
        check("правка нот во время воспроизведения — без сбоев", true);
        // хвост после остановки
        boolean busyAfter = e.busy();
        int tailBlocks = 0; while (e.busy() && tailBlocks < 2000) { e.render(l, r, 256); tailBlocks++; }
        check("после стопа хвосты дозвучивают и движок затихает (" + tailBlocks + " блоков)", busyAfter && !e.busy());
        // mute/solo
        e.play(0);
        for (Track t : p.tracks()) t.mute = true;
        float pk = 0; for (int b = 0; b < 40; b++) { e.render(l, r, 256); if (b > 30) for (float x : l) pk = Math.max(pk, Math.abs(x)); }
        check("все заглушены → тишина", pk < 1e-3);
        for (Track t : p.tracks()) t.mute = false;
        drums.solo = true;
        e.seek(0);
        float pkd = 0; for (int b = 0; b < 40; b++) { e.render(l, r, 256); for (float x : l) pkd = Math.max(pkd, Math.abs(x)); }
        check("соло ударных → звучат", pkd > 0.05);
        drums.solo = false;
        e.stop(); e.close();

        // ---- 6. проект: сохранить → открыть
        File pf = new File(dir, "project.sym");
        ProjectIO.save(p, pf);
        Project q = ProjectIO.load(pf);
        boolean same = q.bpm == p.bpm && q.count() == p.count() && q.master.size() == p.master.size();
        for (int i = 0; same && i < p.count(); i++) {
            Track x = p.tracks()[i], y = q.tracks()[i];
            same = x.id == y.id && x.type == y.type && x.name.equals(y.name) && x.pan == y.pan && x.instrument == y.instrument
                && x.notes().length == y.notes().length && x.fx.size() == y.fx.size() && x.offset == y.offset;
            for (int k = 0; same && k < x.fx.size(); k++) same = java.util.Arrays.equals(x.fx.list()[k].p, y.fx.list()[k].p);
        }
        check("проект: сохранение и загрузка без потерь", same);
        try (FileWriter w = new FileWriter(new File(dir, "broken.sym"))) { w.write("SYMFONIYA\t2\nbpm\tabc\ntrack\t1\t1\t80\n note\nnote\t1\t2\t3\t4\n"); }
        Project br = ProjectIO.load(new File(dir, "broken.sym"));
        check("испорченный проект открывается частично, без падения", br.bpm == 110);

        // ---- 7. MIDI
        File mid = new File(dir, "demo.mid");
        int nt;
        try (OutputStream o = new FileOutputStream(mid)) { nt = MidiFile.write(p, o); }
        byte[] mb = java.nio.file.Files.readAllBytes(mid.toPath());
        int chunks = 0; for (int i = 0; i + 4 <= mb.length; i++) if (mb[i] == 'M' && mb[i + 1] == 'T' && mb[i + 2] == 'r' && mb[i + 3] == 'k') chunks++;
        check("MIDI: " + nt + " дорожек с нотами + темп, заголовок MThd", nt == 6 && chunks == 7 && mb[0] == 'M' && mb[3] == 'd');

        // ---- 8. квантование
        p.quantize = 4;
        check("квантование 1/16: 130 → 120, 185 → 240", p.snap(130) == 120 && p.snap(185) == 240);

        System.out.println(fails == 0 ? "ВСЁ ОК" : "ОШИБОК: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static double[] stats(File f, Wav.Info wi) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        double sum = 0, diff = 0; int peak = 0; long n = wi.frames();
        for (long i = 0; i < n; i++) {
            int o = (int) (wi.dataOffset + i * 4);
            int L = (short) ((b[o] & 0xFF) | (b[o + 1] << 8)), R = (short) ((b[o + 2] & 0xFF) | (b[o + 3] << 8));
            sum += (L * (double) L + R * (double) R) / 2; diff += Math.abs(L - R);
            peak = Math.max(peak, Math.max(Math.abs(L), Math.abs(R)));
        }
        return new double[]{Math.sqrt(sum / n) / 32768, peak / 32768.0, diff / n / 32768};
    }
}
