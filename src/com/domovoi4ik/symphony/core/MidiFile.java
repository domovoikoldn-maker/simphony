package com.domovoi4ik.symphony.core;

import com.domovoi4ik.symphony.synth.Synth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/** Экспорт нот в стандартный MIDI-файл (SMF тип 1) — открывается в FL Studio, Ableton, GarageBand, MuseScore. */
public final class MidiFile {
    /** Ближайшие инструменты General MIDI для наших пресетов. */
    static final int[] GM = {0, 4, 81, 89, 38, 16, 48, 45, 14, 0};

    private MidiFile() { }

    /** @return число дорожек с нотами (0 — нечего экспортировать). */
    public static int write(Project p, OutputStream out) throws IOException {
        ArrayList<Track> list = new ArrayList<Track>();
        for (Track t : p.tracks()) if (t.type == Track.INSTRUMENT && t.notes().length > 0) list.add(t);
        if (list.isEmpty()) return 0;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1});
        be16(all, list.size() + 1);
        be16(all, Project.PPQ);
        // дорожка темпа
        ByteArrayOutputStream tt = new ByteArrayOutputStream();
        int us = 60000000 / p.bpm;
        tt.write(new byte[]{0, (byte) 0xFF, 0x51, 3, (byte) (us >> 16), (byte) (us >> 8), (byte) us});
        tt.write(new byte[]{0, (byte) 0xFF, 0x58, 4, (byte) p.beats, 2, 24, 8});
        text(tt, 0x03, "Симфония · domovoi4ik");
        tt.write(new byte[]{0, (byte) 0xFF, 0x2F, 0});
        chunk(all, tt.toByteArray());
        int ch = 0;
        for (Track t : list) {
            boolean drums = t.instrument == Synth.DRUMS;
            int c = drums ? 9 : (ch == 9 ? ++ch : ch);
            if (!drums) ch++;
            ByteArrayOutputStream tr = new ByteArrayOutputStream();
            text(tr, 0x03, t.name);
            if (!drums) { tr.write(0); tr.write(0xC0 | c); tr.write(GM[Math.max(0, Math.min(GM.length - 1, t.instrument))]); }
            // событие = одно число: тик | порядок (выкл раньше вкл) | статус | нота | сила — сортируется как long
            Note[] ns = t.notes();
            long[] ev = new long[ns.length * 2];
            for (int i = 0; i < ns.length; i++) {
                Note n = ns[i];
                ev[2 * i] = ((long) n.start << 25) | (1L << 24) | ((long) (0x90 | c) << 16) | (n.pitch << 8) | n.vel;
                ev[2 * i + 1] = ((long) n.end() << 25) | ((long) (0x80 | c) << 16) | (n.pitch << 8);
            }
            java.util.Arrays.sort(ev);
            long last = 0;
            for (long e : ev) {
                long tick = e >>> 25;
                vlq(tr, tick - last); last = tick;
                tr.write((int) (e >> 16) & 0xFF); tr.write((int) (e >> 8) & 0x7F); tr.write((int) e & 0x7F);
            }
            tr.write(new byte[]{0, (byte) 0xFF, 0x2F, 0});
            chunk(all, tr.toByteArray());
        }
        out.write(all.toByteArray());
        out.flush();
        return list.size();
    }

    static void chunk(ByteArrayOutputStream all, byte[] data) throws IOException {
        all.write(new byte[]{'M', 'T', 'r', 'k'});
        be32(all, data.length);
        all.write(data);
    }
    static void text(ByteArrayOutputStream o, int kind, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        o.write(0); o.write(0xFF); o.write(kind); vlq(o, b.length); o.write(b);
    }
    static void vlq(ByteArrayOutputStream o, long v) {
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) { buf <<= 8; buf |= ((v & 0x7F) | 0x80); }
        while (true) { o.write((int) (buf & 0xFF)); if ((buf & 0x80) != 0) buf >>= 8; else break; }
    }
    static void be16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }
    static void be32(ByteArrayOutputStream o, int v) { o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v); }
}
