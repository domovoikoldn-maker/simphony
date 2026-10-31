/*
 * «Симфония» 2 — экран инструмента (горизонтальный): пиано-ролл + экранная MIDI-клавиатура
 * + внешние MIDI-клавиатуры. Запись нот поверх песни с метрономом, квантование, отмена.
 * Разработчик и владелец: domovoi4ik.
 */
package com.domovoi4ik.symphony;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.domovoi4ik.symphony.core.Note;
import com.domovoi4ik.symphony.core.Project;
import com.domovoi4ik.symphony.core.Track;
import com.domovoi4ik.symphony.synth.Synth;
import com.domovoi4ik.symphony.ui.PianoKeyboardView;
import com.domovoi4ik.symphony.ui.PianoRollView;

public class InstrumentActivity extends Activity implements View.OnClickListener, Studio.Listener,
        PianoKeyboardView.Listener, PianoRollView.Listener, MidiInput.Sink {
    static final int[] GRID = {1, 2, 4, 8, 0};
    static final String[] GRID_NAME = {"1/4", "1/8", "1/16", "1/32", "выкл"};

    Studio s;
    Project p;
    Track t;
    PianoRollView roll;
    PianoKeyboardView keys;
    Button bInstr, bPlay, bRec, bUndo, bGrid, bMetro;
    TextView tvOct, tvStatus;
    MidiInput midi;
    String midiNames = "";
    final Ticker ticker = new Ticker(this);
    int color;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        s = Studio.get(this);
        p = s.project;
        t = p.byId(getIntent().getIntExtra("track", -1));
        if (t == null || t.type != Track.INSTRUMENT) { finish(); return; }
        setContentView(R.layout.activity_instrument);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        color = MainActivity.COLORS[(t.id - 1) % MainActivity.COLORS.length];
        roll = (PianoRollView) findViewById(R.id.iRoll);
        keys = (PianoKeyboardView) findViewById(R.id.iKeys);
        bInstr = (Button) findViewById(R.id.iInstr);
        bPlay = (Button) findViewById(R.id.iPlay);
        bRec = (Button) findViewById(R.id.iRec);
        bUndo = (Button) findViewById(R.id.iUndo);
        bGrid = (Button) findViewById(R.id.iGrid);
        bMetro = (Button) findViewById(R.id.iMetro);
        tvOct = (TextView) findViewById(R.id.iOct);
        tvStatus = (TextView) findViewById(R.id.iStatus);
        for (int id : new int[]{R.id.iBack, R.id.iInstr, R.id.iPlay, R.id.iRec, R.id.iUndo, R.id.iGrid, R.id.iMetro,
            R.id.iOctDown, R.id.iOctUp, R.id.iZoomOut, R.id.iZoomIn, R.id.iFx, R.id.iMore})
            findViewById(id).setOnClickListener(this);
        roll.bind(p, t, color, this);
        keys.setListener(this);
        if (t.instrument == Synth.DRUMS) { keys.shiftOctave(-1); }
        midi = new MidiInput(this, this, s.ui);
    }
    @Override protected void onStart() { super.onStart(); s.onScreenStart(); }
    @Override protected void onResume() {
        super.onResume();
        s.setListener(this);
        s.output.setKeepAlive(true);       // клавиши звучат сразу, без запуска звука
        midi.start();
        refresh();
        s.ui.post(ticker);
    }
    @Override protected void onPause() {
        s.ui.removeCallbacks(ticker);
        keys.releaseAll();
        midi.stop();
        if (s.engine.midiRecordTrack() >= 0) finishRec();
        s.output.setKeepAlive(false);
        s.clearListener(this);
        super.onPause();
    }
    @Override protected void onStop() { s.onScreenStop(); super.onStop(); }

    @Override public void onStudioChanged(String message) {
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        refresh();
    }

    void refresh() {
        boolean playing = s.playing(), rec = s.engine.midiRecordTrack() >= 0;
        bInstr.setText(Synth.NAMES[t.instrument] + " ▾");
        bPlay.setText(playing ? "■" : "▶");
        bPlay.setActivated(playing && !rec);
        bRec.setText(rec ? "■ Стоп записи" : "● Запись нот");
        bRec.setActivated(rec);
        bUndo.setEnabled(s.canUndo(t));
        bGrid.setText("Сетка " + GRID_NAME[gridIndex()]);
        bMetro.setActivated(p.metronome);
        keys.setDrums(t.instrument == Synth.DRUMS);
        tvOct.setText(keys.rangeLabel());
        status();
        roll.invalidate();
    }
    void status() {
        String m = midiNames.length() > 0 ? "MIDI: " + midiNames : (MidiInput.supported(this) ? "MIDI: подключите клавиатуру по USB" : "");
        tvStatus.setText(t.name + " · " + t.notes().length + " нот" + (m.length() > 0 ? " · " + m : ""));
    }
    int gridIndex() { for (int i = 0; i < GRID.length; i++) if (GRID[i] == p.quantize) return i; return 2; }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.iBack) finish();
        else if (id == R.id.iInstr) new AlertDialog.Builder(this).setTitle("Инструмент").setItems(Synth.NAMES, new Pick(this)).show();
        else if (id == R.id.iPlay) {
            if (s.engine.midiRecordTrack() >= 0) finishRec();
            else if (s.playing()) s.stopTransport();
            else s.play();
        } else if (id == R.id.iRec) {
            if (s.engine.midiRecordTrack() >= 0) finishRec();
            else { s.startMidiRecord(t); Toast.makeText(this, "Играйте! Ноты запишутся поверх уже имеющихся.", Toast.LENGTH_SHORT).show(); }
        } else if (id == R.id.iUndo) { if (s.undo(t)) roll.invalidate(); }
        else if (id == R.id.iGrid) { p.quantize = GRID[(gridIndex() + 1) % GRID.length]; s.changed(); }
        else if (id == R.id.iMetro) { p.metronome = !p.metronome; s.changed(); }
        else if (id == R.id.iOctDown) keys.shiftOctave(-1);
        else if (id == R.id.iOctUp) keys.shiftOctave(1);
        else if (id == R.id.iZoomOut) roll.zoom(0.7f);
        else if (id == R.id.iZoomIn) roll.zoom(1.4f);
        else if (id == R.id.iFx) new FxEditor(this, s, t.fx, "Эффекты — " + t.name).show();
        else if (id == R.id.iMore) more(v);
        refresh();
    }
    void finishRec() {
        int n = s.finishMidiRecord();
        Toast.makeText(this, n > 0 ? "Записано нот: " + n + (p.quantize > 0 ? " (выровнены по сетке " + GRID_NAME[gridIndex()] + ")" : "") : "Ничего не сыграно", Toast.LENGTH_SHORT).show();
        refresh();
    }
    void more(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Выровнять все ноты по сетке");
        menu.add(0, 2, 1, "Удвоить (повторить ноты ещё раз)");
        menu.add(0, 3, 2, "Удалить все ноты");
        menu.add(0, 4, 3, "В начало песни");
        m.setOnMenuItemClickListener(new More(this));
        m.show();
    }
    void quantizeAll() {
        if (p.quantize <= 0) { Toast.makeText(this, "Включите сетку", Toast.LENGTH_SHORT).show(); return; }
        s.pushUndo(t);
        Note[] ns = t.notes(), out = new Note[ns.length];
        int g = p.gridTicks();
        for (int i = 0; i < ns.length; i++) out[i] = new Note(p.snap(ns[i].start), Math.max(g, p.snap(ns[i].len)), ns[i].pitch, ns[i].vel);
        t.setNotes(out);
        s.changed();
    }
    void duplicate() {
        Note[] ns = t.notes();
        if (ns.length == 0) return;
        int bar = Project.PPQ * p.beats;
        int span = ((t.lastTick() + bar - 1) / bar) * bar;
        s.pushUndo(t);
        Note[] out = new Note[ns.length * 2];
        for (int i = 0; i < ns.length; i++) { out[i] = ns[i]; Note n = ns[i]; out[ns.length + i] = new Note(n.start + span, n.len, n.pitch, n.vel); }
        t.setNotes(out);
        s.changed();
    }

    // ---- экранная клавиатура ----
    @Override public void onKeyDown(int pitch, int vel) { s.noteOn(t, pitch, vel); }
    @Override public void onKeyUp(int pitch) { s.noteOff(t, pitch); }
    // ---- внешняя MIDI-клавиатура (поток MIDI) ----
    @Override public void onMidiNote(int pitch, int vel) {
        if (vel > 0) s.engine.noteOn(t.id, pitch, vel); else s.engine.noteOff(t.id, pitch);
        if (vel > 0) s.output.ensureRunning();
        keys.setExternal(pitch, vel > 0);
    }
    @Override public void onMidiDevices(String names) {
        midiNames = names;
        if (names.length() > 0) Toast.makeText(this, "MIDI-клавиатура: " + names, Toast.LENGTH_SHORT).show();
        status();
    }
    // ---- пиано-ролл ----
    @Override public void audition(int pitch, boolean on) { if (on) s.noteOn(t, pitch, 100); else s.noteOff(t, pitch); }
    @Override public void seek(long tick) { s.seek(p.tickToFrame(tick)); roll.setPlayheadTick(tick); }
    @Override public void beforeEdit() { s.pushUndo(t); }
    @Override public void edited() { s.changed(); bUndo.setEnabled(true); status(); }

    static final class Ticker implements Runnable {
        final InstrumentActivity a; boolean was;
        Ticker(InstrumentActivity a) { this.a = a; }
        @Override public void run() {
            boolean playing = a.s.playing();
            long frame = Math.max(0, a.s.engine.position() - (playing ? a.s.output.latencyFrames() : 0));
            long tick = a.p.frameToTick(frame);
            a.roll.setPlayheadTick(tick);
            if (playing) { a.roll.follow(tick); if (a.s.engine.midiRecordTrack() >= 0) a.roll.invalidate(); }
            if (playing != was) a.refresh();
            String oct = a.keys.rangeLabel();
            if (!oct.contentEquals(a.tvOct.getText())) a.tvOct.setText(oct);
            was = playing;
            a.s.ui.postDelayed(this, 33);
        }
    }
    static final class Pick implements DialogInterface.OnClickListener {
        final InstrumentActivity a;
        Pick(InstrumentActivity a) { this.a = a; }
        @Override public void onClick(DialogInterface d, int which) {
            boolean defaultName = a.t.name.startsWith("Инструмент");
            for (String n : Synth.NAMES) if (a.t.name.startsWith(n)) defaultName = true;
            a.keys.releaseAll();
            a.t.instrument = which;
            if (defaultName) a.t.name = Synth.NAMES[which];
            a.s.engine.panic();
            a.s.changed();
            a.roll.bind(a.p, a.t, a.color, a);
            a.refresh();
        }
    }
    static final class More implements PopupMenu.OnMenuItemClickListener {
        final InstrumentActivity a;
        More(InstrumentActivity a) { this.a = a; }
        @Override public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case 1: a.quantizeAll(); break;
                case 2: a.duplicate(); break;
                case 3: a.s.clearTrack(a.t); break;
                case 4: a.s.rewind(); break;
                default: return false;
            }
            a.refresh();
            return true;
        }
    }
}
