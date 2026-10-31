/*
 * «Симфония» — студия: один проект, один движок и один звуковой выход на всё приложение.
 * Разработчик и владелец: domovoi4ik.
 *
 * Studio живёт, пока жив процесс: главный экран и экран инструмента работают с одним и тем же
 * проектом, а звук не прерывается при переходе между ними. Сохраняет проект с задержкой 0,6 с
 * после изменений, при уходе в фон — сразу, и останавливает звук, когда приложение не видно.
 */
package com.domovoi4ik.symphony;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.domovoi4ik.symphony.audio.Recorder;
import com.domovoi4ik.symphony.audio.Wav;
import com.domovoi4ik.symphony.core.Engine;
import com.domovoi4ik.symphony.core.Note;
import com.domovoi4ik.symphony.core.Project;
import com.domovoi4ik.symphony.core.ProjectIO;
import com.domovoi4ik.symphony.core.Track;
import com.domovoi4ik.symphony.dsp.Effects;
import com.domovoi4ik.symphony.synth.Synth;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public final class Studio {
    /** Экраны подписываются, чтобы узнать о конце записи, ошибках и т. п. Всё — на главном потоке. */
    public interface Listener { void onStudioChanged(String message); }

    private static Studio inst;
    public static synchronized Studio get(Context c) {
        if (inst == null) inst = new Studio(c.getApplicationContext());
        return inst;
    }

    public final Context ctx;
    public final File dir;
    public final Project project;
    public final Engine engine;
    public final Output output;
    public final Recorder recorder = new Recorder();
    public final Handler ui = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;
    public String loadWarning;
    private Listener listener;
    private int visible;
    private final Saver saver = new Saver(this);
    // запись
    public Track recordingTrack;
    private long recordStart;
    private File recordTake;
    // отмена (последние 30 правок нот)
    private final ArrayList<Object[]> undo = new ArrayList<Object[]>();

    private Studio(Context c) {
        ctx = c;
        dir = new File(c.getFilesDir(), "project");
        if (!dir.isDirectory()) dir.mkdirs();
        prefs = c.getSharedPreferences("studio", Context.MODE_PRIVATE);
        project = loadProject();
        engine = new Engine(project, dir, false);
        output = new Output(engine, this);
    }

    // ================================================================== проект
    File projectFile() { return new File(dir, "project.sym"); }

    private Project loadProject() {
        File f = projectFile();
        Project p = null;
        if (f.isFile()) {
            try { p = ProjectIO.load(f); }
            catch (IOException e) { loadWarning = "Проект не открылся (" + e.getMessage() + "). Создан новый, старый файл сохранён как project.sym.bad"; f.renameTo(new File(dir, "project.sym.bad")); }
        }
        if (p == null) p = migrateV1();
        if (p == null) p = starter();
        for (Track t : p.tracks()) refreshAudioLength(t);
        return p;
    }
    void refreshAudioLength(Track t) {
        t.frames = 0;
        if (t.type != Track.AUDIO || t.file == null) return;
        File w = new File(dir, t.file);
        if (!w.isFile()) return;
        try { t.frames = Wav.read(w).frames(); } catch (IOException e) { loadWarning = "Запись «" + t.name + "» повреждена и пропущена"; }
    }
    /** Перенос дорожек из «Симфонии» 1.0 (files/tracks/track1..4.wav). */
    private Project migrateV1() {
        File old = new File(ctx.getFilesDir(), "tracks");
        if (!old.isDirectory()) return null;
        SharedPreferences op = ctx.getSharedPreferences("project", Context.MODE_PRIVATE);
        Project p = new Project();
        for (int i = 0; i < 4; i++) {
            File w = new File(old, "track" + (i + 1) + ".wav");
            if (!w.isFile()) continue;
            Track t = p.add(Track.AUDIO, op.getString("name" + i, "Дорожка " + (i + 1)));
            t.volume = op.getInt("vol" + i, 80);
            t.mute = op.getBoolean("mute" + i, false);
            String name = "a" + t.id + "_1.wav";
            if (w.renameTo(new File(dir, name))) t.file = name;
        }
        if (p.count() == 0) return null;
        prefs.edit().putInt("cleanExports", op.getInt("cleanExports", 0)).putInt("latencyMs", op.getInt("latencyMs", 0))
            .putInt("adConsent", op.getInt("adConsent", -1)).apply();
        return p;
    }
    /** Новый проект: голос, пианино и ударные с ритмом — чтобы сразу было что послушать. */
    private static Project starter() {
        Project p = new Project();
        p.add(Track.AUDIO, "Голос");
        Track keys = p.add(Track.INSTRUMENT, "Пианино");
        keys.instrument = Synth.PIANO;
        keys.fx.add(Effects.create(Effects.REVERB));
        Track drums = p.add(Track.INSTRUMENT, "Ударные");
        drums.instrument = Synth.DRUMS;
        drums.volume = 70;
        ArrayList<Note> n = new ArrayList<Note>();
        int q = Project.PPQ;
        for (int bar = 0; bar < 2; bar++) for (int b = 0; b < 4; b++) {
            int t = (bar * 4 + b) * q;
            n.add(new Note(t, q / 4, b % 2 == 0 ? 36 : 38, 105));
            n.add(new Note(t, q / 4, 42, 70));
            n.add(new Note(t + q / 2, q / 4, 42, 55));
        }
        drums.setNotes(n);
        return p;
    }

    public void changed() { ui.removeCallbacks(saver); ui.postDelayed(saver, 600); }
    public String saveNow() {
        ui.removeCallbacks(saver);
        try { ProjectIO.save(project, projectFile()); return null; }
        catch (IOException e) { return "Проект не сохранён: " + e.getMessage(); }
    }
    static final class Saver implements Runnable {
        final Studio s; Saver(Studio s) { this.s = s; }
        @Override public void run() { String e = s.saveNow(); if (e != null) s.notifyUi(e); }
    }

    public int cleanExports() { return prefs.getInt("cleanExports", 0); }
    public void setCleanExports(int n) { prefs.edit().putInt("cleanExports", Math.max(0, n)).apply(); }
    public int latencyMs() { return prefs.getInt("latencyMs", 120); }
    public void setLatencyMs(int v) { prefs.edit().putInt("latencyMs", v).apply(); }
    public int adConsent() { return prefs.getInt("adConsent", -1); }
    public void setAdConsent(int v) { prefs.edit().putInt("adConsent", v).apply(); }

    // ================================================================== видимость и слушатели
    public void setListener(Listener l) { listener = l; }
    public void clearListener(Listener l) { if (listener == l) listener = null; }
    void notifyUi(String msg) { if (listener != null) listener.onStudioChanged(msg); }
    /** Экран стал видимым / невидимым. Когда не видно ни одного — останавливаем звук и сохраняем. */
    public void onScreenStart() { visible++; }
    public void onScreenStop() {
        visible = Math.max(0, visible - 1);
        if (visible == 0) {
            if (recorder.isRecording()) recorder.stopAndWait();
            stopTransport();
            engine.panic();
            saveNow();
        }
    }

    // ================================================================== транспорт
    public boolean playing() { return engine.playing(); }
    public void play() {
        if (recorder.isRecording()) return;
        requestFocus();
        long len = project.lengthFrames();
        long pos = engine.position();
        if (len > 0 && pos >= len) pos = 0;
        engine.play(pos);
        output.ensureRunning();
    }
    public void stopTransport() {
        if (recorder.isRecording()) { recorder.stop(); return; }
        if (engine.midiRecordTrack() >= 0) { finishMidiRecord(); return; }
        engine.stop();
    }
    // ---- аудиофокус: звонок или другой плеер — останавливаемся ----
    private final Focus focus = new Focus(this);
    @SuppressWarnings("deprecation")
    void requestFocus() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.requestAudioFocus(focus, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN);
        } catch (RuntimeException ignored) { }
    }
    static final class Focus implements android.media.AudioManager.OnAudioFocusChangeListener {
        final Studio s; Focus(Studio s) { this.s = s; }
        @Override public void onAudioFocusChange(int c) {
            if (c == android.media.AudioManager.AUDIOFOCUS_LOSS || c == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) s.ui.post(new FocusLoss(s));
        }
    }
    static final class FocusLoss implements Runnable {
        final Studio s; FocusLoss(Studio s) { this.s = s; }
        @Override public void run() { if (s.engine.playing()) { s.stopTransport(); s.notifyUi(null); } }
    }
    public void rewind() { engine.seek(0); }
    public void seek(long frame) { engine.seek(frame); output.ensureRunning(); }

    // ---- живые ноты ----
    public void noteOn(Track t, int pitch, int vel) { engine.noteOn(t.id, pitch, vel); output.ensureRunning(); }
    public void noteOff(Track t, int pitch) { engine.noteOff(t.id, pitch); }

    // ================================================================== запись нот
    public void startMidiRecord(Track t) {
        if (recorder.isRecording()) return;
        engine.stop();
        engine.startMidiRecord(t.id);
        engine.play(engine.position());
        output.ensureRunning();
    }
    /** Остановить запись нот: дописать сыгранное в дорожку (с квантованием по сетке проекта). */
    public int finishMidiRecord() {
        int id = engine.midiRecordTrack();
        ArrayList<Note> rec = engine.stopMidiRecord();
        engine.stop();
        Track t = project.byId(id);
        if (t == null || rec.isEmpty()) return 0;
        pushUndo(t);
        Note[] old = t.notes();
        Note[] all = new Note[old.length + rec.size()];
        System.arraycopy(old, 0, all, 0, old.length);
        int g = project.gridTicks();
        for (int i = 0; i < rec.size(); i++) {
            Note n = rec.get(i);
            int s = project.quantize > 0 ? project.snap(n.start) : n.start;
            int len = project.quantize > 0 ? Math.max(g, project.snap(n.len)) : n.len;
            all[old.length + i] = new Note(s, len, n.pitch, n.vel);
        }
        t.setNotes(all);
        changed();
        return rec.size();
    }

    // ---- отмена ----
    public void pushUndo(Track t) {
        undo.add(new Object[]{t, t.notes()});
        if (undo.size() > 30) undo.remove(0);
    }
    public boolean canUndo(Track t) { for (Object[] u : undo) if (u[0] == t) return true; return false; }
    public boolean undo(Track t) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            Object[] u = undo.get(i);
            if (u[0] == t) { undo.remove(i); t.setNotes((Note[]) u[1]); changed(); return true; }
        }
        return false;
    }

    // ================================================================== запись с микрофона
    /** Начать запись в аудиодорожку с текущей позиции; остальные дорожки звучат. null — успех. */
    public String startAudioRecord(Track t) {
        if (recorder.isRecording()) return "Запись уже идёт";
        engine.stop();
        recordStart = engine.position();
        recordTake = new File(dir, "take_" + t.id + ".tmp");
        engine.exclude(t.id);
        String err = recorder.start(t.id, recordTake, latencyMs(), new RecDone(this));
        if (err != null) { engine.exclude(-1); return err; }
        recordingTrack = t;
        requestFocus();
        engine.play(recordStart);
        output.ensureRunning();
        return null;
    }
    public long recordedFrames() { return recorder.frames(); }
    public long recordStart() { return recordStart; }

    void recordFinished(long frames, String error) {
        Track t = recordingTrack;
        recordingTrack = null;
        engine.stop();
        engine.exclude(-1);
        String msg = error;
        if (t == null) return;
        if (frames < Wav.RATE / 5) {
            recordTake.delete();
            if (msg == null) msg = "Слишком короткая запись — дорожка не изменена";
        } else {
            String name = "a" + t.id + "_" + System.currentTimeMillis() % 100000000L + ".wav";
            File dst = new File(dir, name);
            if (!recordTake.renameTo(dst)) msg = "Не удалось сохранить запись";
            else {
                String oldFile = t.file;
                t.file = name;
                t.offset = recordStart;
                t.frames = frames;
                if (oldFile != null && !oldFile.equals(name)) new File(dir, oldFile).delete();
                engine.seek(recordStart);
                changed();
                if (msg != null) msg += "\nЗаписанное сохранено.";
            }
        }
        notifyUi(msg);
    }
    static final class RecDone implements Recorder.Listener {
        final Studio s; RecDone(Studio s) { this.s = s; }
        @Override public void onRecordFinished(int track, File take, long frames, String error) { s.ui.post(new RecPost(s, frames, error)); }
    }
    static final class RecPost implements Runnable {
        final Studio s; final long frames; final String error;
        RecPost(Studio s, long frames, String error) { this.s = s; this.frames = frames; this.error = error; }
        @Override public void run() { s.recordFinished(frames, error); }
    }

    // ================================================================== дорожки
    public Track addTrack(int type) {
        int n = 1;
        for (Track t : project.tracks()) if (t.type == type) n++;
        Track t = project.add(type, type == Track.AUDIO ? "Аудио " + n : "Инструмент " + n);
        if (t != null && type == Track.INSTRUMENT) { t.instrument = Synth.PIANO; t.name = Synth.NAMES[Synth.PIANO] + (n > 1 ? " " + n : ""); }
        changed();
        return t;
    }
    public void deleteTrack(Track t) {
        if (recordingTrack == t) return;
        engine.stop();
        project.remove(t);
        engine.sync();
        if (t.file != null) new File(dir, t.file).delete();
        changed();
    }
    public void clearTrack(Track t) {
        engine.stop();
        if (t.type == Track.AUDIO) {
            if (t.file != null) new File(dir, t.file).delete();
            t.file = null; t.frames = 0; t.offset = 0;
        } else { pushUndo(t); t.setNotes(new Note[0]); }
        changed();
    }

    /** Ошибка, которую поймал звуковой поток. */
    void outputError(String e) { notifyUi(e); }
}
