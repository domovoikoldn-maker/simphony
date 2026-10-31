package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.dsp.Effect;
import com.domovoi4ik.symphony.dsp.Effects;
import com.domovoi4ik.symphony.dsp.FxChain;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

/**
 * Сохранение проекта в простой текстовый файл project.sym (строки «ключ\tзначения»).
 * Пишется во временный файл и переименовывается — при сбое старый проект не портится.
 * Звук лежит рядом отдельными WAV, ноты и эффекты — в этом файле.
 */
public final class ProjectIO {
    public static final String MAGIC = "SYMFONIYA\t2";
    private ProjectIO() { }

    public static void save(Project p, File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            StringBuilder b = new StringBuilder(4096);
            b.append(MAGIC).append('\n');
            b.append("bpm\t").append(p.bpm).append('\n');
            b.append("beats\t").append(p.beats).append('\n');
            b.append("metronome\t").append(p.metronome ? 1 : 0).append('\n');
            b.append("master\t").append(p.masterVolume).append('\n');
            b.append("quantize\t").append(p.quantize).append('\n');
            fx(b, "mfx", p.master);
            for (Track t : p.tracks()) {
                b.append("track\t").append(t.id).append('\t').append(t.type).append('\t').append(t.volume).append('\t').append(t.pan)
                    .append('\t').append(t.mute ? 1 : 0).append('\t').append(t.solo ? 1 : 0).append('\t').append(t.instrument)
                    .append('\t').append(t.file == null ? "-" : t.file).append('\t').append(t.offset).append('\t').append(clean(t.name)).append('\n');
                fx(b, "fx", t.fx);
                for (Note n : t.notes()) b.append("note\t").append(n.start).append('\t').append(n.len).append('\t').append(n.pitch).append('\t').append(n.vel).append('\n');
            }
            w.write(b.toString());
        } finally { w.close(); }
        if (f.exists() && !f.delete()) throw new IOException("не удалось заменить проект");
        if (!tmp.renameTo(f)) throw new IOException("не удалось сохранить проект");
    }
    static void fx(StringBuilder b, String key, FxChain c) {
        for (Effect e : c.list()) {
            b.append(key).append('\t').append(e.type).append('\t').append(e.on ? 1 : 0).append('\t');
            for (int i = 0; i < e.p.length; i++) { if (i > 0) b.append(','); b.append(e.p[i]); }
            b.append('\n');
        }
    }
    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    /** Прочитать проект. Неизвестные/битые строки пропускаются — лучше открыть часть, чем ничего. */
    public static Project load(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String first = r.readLine();
            if (first == null || !first.startsWith("SYMFONIYA")) throw new IOException("это не проект Симфонии");
            Project p = new Project();
            Track cur = null;
            ArrayList<Note> notes = new ArrayList<Note>();
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.split("\t", -1);
                try {
                    switch (a[0]) {
                        case "bpm": p.bpm = clamp(Integer.parseInt(a[1]), 30, 300); break;
                        case "beats": p.beats = clamp(Integer.parseInt(a[1]), 2, 12); break;
                        case "metronome": p.metronome = "1".equals(a[1]); break;
                        case "master": p.masterVolume = clamp(Integer.parseInt(a[1]), 0, 100); break;
                        case "quantize": p.quantize = clamp(Integer.parseInt(a[1]), 0, 8); break;
                        case "mfx": readFx(a, p.master); break;
                        case "track": {
                            if (cur != null) cur.setNotes(notes);
                            notes.clear();
                            cur = new Track(Integer.parseInt(a[1]), Integer.parseInt(a[2]) == 1 ? Track.INSTRUMENT : Track.AUDIO, a[10]);
                            cur.volume = clamp(Integer.parseInt(a[3]), 0, 100);
                            cur.pan = clamp(Integer.parseInt(a[4]), -100, 100);
                            cur.mute = "1".equals(a[5]); cur.solo = "1".equals(a[6]);
                            cur.instrument = clamp(Integer.parseInt(a[7]), 0, com.domovoi4ik.symphony.synth.Synth.NAMES.length - 1);
                            cur.file = "-".equals(a[8]) ? null : a[8];
                            cur.offset = Math.max(0, Long.parseLong(a[9]));
                            p.addLoaded(cur);
                            break;
                        }
                        case "fx": if (cur != null) readFx(a, cur.fx); break;
                        case "note": if (cur != null) notes.add(new Note(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]))); break;
                        default: break;
                    }
                } catch (RuntimeException ignored) { /* битая строка */ }
            }
            if (cur != null) cur.setNotes(notes);
            return p;
        } finally { r.close(); }
    }
    static void readFx(String[] a, FxChain c) {
        Effect e = Effects.create(Integer.parseInt(a[1]));
        if (e == null) return;
        e.on = "1".equals(a[2]);
        String[] v = a[3].split(",");
        for (int i = 0; i < Math.min(v.length, e.p.length); i++) e.p[i] = Math.max(0, Math.min(1, Float.parseFloat(v[i])));
        c.add(e);
    }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
