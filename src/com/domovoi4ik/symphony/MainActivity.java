/*
 * «Симфония» 2 — главный экран: транспорт, темп, метроном, мастер, дорожки (до 8), экспорт, реклама.
 * Разработчик и владелец: domovoi4ik · domovoikold@gmail.com
 *
 * Вертикальный экран (реклама при открытии у Яндекса — только для вертикальной ориентации).
 * Только статические вложенные классы — без лямбд и анонимных классов (собирается и без Gradle).
 */
package com.domovoi4ik.symphony;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.domovoi4ik.symphony.ads.Ads;
import com.domovoi4ik.symphony.audio.Peaks;
import com.domovoi4ik.symphony.core.Exporter;
import com.domovoi4ik.symphony.core.MidiFile;
import com.domovoi4ik.symphony.core.Mix;
import com.domovoi4ik.symphony.core.Project;
import com.domovoi4ik.symphony.core.Track;
import com.domovoi4ik.symphony.synth.Synth;
import com.domovoi4ik.symphony.ui.TrackView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity implements View.OnClickListener, Studio.Listener, Ads.Callback {
    static final int REQ_MIC = 1, REQ_WAV = 2, REQ_MID = 3;
    static final int[] COLORS = {0xFFE8B04B, 0xFF5EB3F0, 0xFF7BD389, 0xFFC98BEF, 0xFFF07B6E, 0xFF5ED0C8, 0xFFF0D35E, 0xFFB0B8C8};
    static boolean coldStartUsed;           // реклама при открытии — один раз за жизнь процесса

    Studio s;
    Project p;
    Ads ads;
    boolean inAd, adRewardPending;
    TextView tvBar, tvTime;
    Button btnPlay, btnTempo, btnMetro, btnExport, btnAd, btnAddAudio, btnAddInstr;
    LinearLayout list;
    final ArrayList<Row> rows = new ArrayList<Row>();
    final HashMap<String, float[]> peaks = new HashMap<String, float[]>();
    final java.util.HashSet<String> peaksPending = new java.util.HashSet<String>();
    final Ticker ticker = new Ticker(this);
    Track pendingRecord;
    volatile boolean exporting;
    ExportTask export;
    AlertDialog exportDialog;
    ProgressBar exportBar;

    static final class Row {
        Track t;
        View root;
        Button main, mute, solo, fx, more;
        TextView name, info;
        TrackView view;
        SeekBar vol, pan;
        int color;
    }

    // ================================================================== жизненный цикл
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        s = Studio.get(this);
        p = s.project;
        tvBar = (TextView) findViewById(R.id.tvBar);
        tvTime = (TextView) findViewById(R.id.tvTime);
        btnPlay = (Button) findViewById(R.id.btnPlay);
        btnTempo = (Button) findViewById(R.id.btnTempo);
        btnMetro = (Button) findViewById(R.id.btnMetro);
        btnExport = (Button) findViewById(R.id.btnExport);
        btnAd = (Button) findViewById(R.id.btnAd);
        btnAddAudio = (Button) findViewById(R.id.btnAddAudio);
        btnAddInstr = (Button) findViewById(R.id.btnAddInstr);
        list = (LinearLayout) findViewById(R.id.tracks);
        for (int id : new int[]{R.id.btnPlay, R.id.btnRewind, R.id.btnTempo, R.id.btnMetro, R.id.btnMaster, R.id.btnExport,
            R.id.btnAd, R.id.btnPrivacy, R.id.btnMenu, R.id.btnAddAudio, R.id.btnAddInstr})
            findViewById(id).setOnClickListener(this);
        boolean cold = saved == null && !coldStartUsed;
        coldStartUsed = true;
        ads = createAds(cold);
        if (s.loadWarning != null) { alert("Внимание", s.loadWarning); s.loadWarning = null; }
        if (ads.available() && s.adConsent() < 0) askConsent();
    }
    @Override protected void onStart() { super.onStart(); s.onScreenStart(); }
    @Override protected void onResume() {
        super.onResume();
        s.setListener(this);
        rebuildRows();
        refresh();
        startTicker();
    }
    @Override protected void onPause() {
        s.clearListener(this);
        ui().removeCallbacks(ticker);
        super.onPause();
    }
    @Override protected void onStop() { s.onScreenStop(); super.onStop(); }
    @Override protected void onDestroy() {
        try { ads.destroy(); } catch (Throwable t) { /* реклама не должна ронять приложение */ }
        if (exportDialog != null) exportDialog.dismiss();
        super.onDestroy();
    }
    android.os.Handler ui() { return s.ui; }

    /** Сообщение от студии: конец записи, ошибка звука и т. п. */
    @Override public void onStudioChanged(String message) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (message != null) alert("Симфония", message);
        refresh();
    }

    // ================================================================== дорожки
    void rebuildRows() {
        list.removeAllViews();
        rows.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        Track[] tr = p.tracks();
        for (int i = 0; i < tr.length; i++) {
            Track t = tr[i];
            Row r = new Row();
            r.t = t;
            r.color = COLORS[(t.id - 1) % COLORS.length];
            r.root = inf.inflate(R.layout.track_row, list, false);
            r.main = (Button) r.root.findViewById(R.id.rowMain);
            r.mute = (Button) r.root.findViewById(R.id.rowMute);
            r.solo = (Button) r.root.findViewById(R.id.rowSolo);
            r.fx = (Button) r.root.findViewById(R.id.rowFx);
            r.more = (Button) r.root.findViewById(R.id.rowMore);
            r.name = (TextView) r.root.findViewById(R.id.rowName);
            r.info = (TextView) r.root.findViewById(R.id.rowInfo);
            r.view = (TrackView) r.root.findViewById(R.id.rowView);
            r.vol = (SeekBar) r.root.findViewById(R.id.rowVolume);
            r.pan = (SeekBar) r.root.findViewById(R.id.rowPan);
            r.name.setTextColor(r.color);
            r.view.bind(p, t, r.color);
            for (View v : new View[]{r.main, r.mute, r.solo, r.fx, r.more, r.view, r.name}) { v.setTag(r); v.setOnClickListener(this); }
            if (t.type == Track.INSTRUMENT) r.main.setBackgroundResource(R.drawable.btn);
            r.vol.setProgress(t.volume);
            r.pan.setProgress(t.pan + 100);
            r.vol.setOnSeekBarChangeListener(new Slider(this, t, false));
            r.pan.setOnSeekBarChangeListener(new Slider(this, t, true));
            r.main.setContentDescription(t.type == Track.AUDIO ? "Записать " + t.name : "Клавиши и ноты " + t.name);
            list.addView(r.root);
            rows.add(r);
        }
    }
    void computePeaks(Track t) {
        if (t.file == null || peaks.containsKey(t.file) || !peaksPending.add(t.file)) return;
        new Thread(new PeaksTask(this, t.file, new File(s.dir, t.file)), "peaks").start();
    }

    void refresh() {
        boolean rec = s.recorder.isRecording(), play = s.playing();
        long len = p.lengthFrames();
        long view = Math.max(len, s.engine.position()) + p.framesPerBar();
        btnPlay.setText(rec || play ? "■" : "▶");
        btnPlay.setActivated(rec || play);
        btnTempo.setText(p.bpm + " BPM · " + p.beats + "/4");
        btnMetro.setActivated(p.metronome);
        boolean full = p.count() >= Project.MAX_TRACKS;
        btnAddAudio.setEnabled(!full && !rec); btnAddInstr.setEnabled(!full && !rec);
        btnExport.setEnabled(!rec && !exporting && len > 0);
        int clean = s.cleanExports();
        if (ads.available()) {
            btnExport.setText(clean > 0 ? "Экспорт WAV · без метки" : "Экспорт WAV");
            btnAd.setVisibility(View.VISIBLE);
            btnAd.setText("▶ Реклама → экспорт без звуковой метки" + (clean > 0 ? "  (в запасе: " + clean + ")" : ""));
            btnAd.setEnabled(!rec && !inAd);
        } else { btnExport.setText("Экспорт WAV"); btnAd.setVisibility(View.GONE); }
        for (Row r : rows) {
            Track t = r.t;
            boolean recThis = rec && s.recordingTrack == t;
            r.name.setText(t.name);
            String kind = t.type == Track.AUDIO ? "Аудио" : Synth.NAMES[Math.max(0, Math.min(Synth.NAMES.length - 1, t.instrument))];
            String content = t.type == Track.AUDIO ? (t.frames > 0 ? Mix.time(t.frames) : "пусто") : (t.notes().length + " нот");
            int nfx = t.fx.size();
            r.info.setText(kind + " · " + content + (nfx > 0 ? " · FX " + nfx : "") + (t.pan != 0 ? " · пан " + (t.pan > 0 ? "П" : "Л") + Math.abs(t.pan) : ""));
            r.main.setText(t.type == Track.AUDIO ? (recThis ? "■" : "●") : "⌨");
            r.main.setActivated(recThis);
            r.main.setEnabled(t.type == Track.INSTRUMENT ? !rec : (!rec || recThis));
            r.mute.setActivated(t.mute);
            r.solo.setActivated(t.solo);
            r.fx.setActivated(nfx > 0);
            r.view.setView(view);
            r.view.setLabel(t.hasContent() || recThis ? "" : (t.type == Track.AUDIO ? "пусто — нажмите ●, чтобы записать" : "нет нот — нажмите ⌨"));
            if (t.type == Track.AUDIO) computePeaks(t);
            if (t.type == Track.AUDIO) r.view.setPeaks(t.file == null ? null : peaks.get(t.file), t.frames);
            if (!recThis) r.view.setLevel(-1);
        }
        tick();
    }
    /** Время, курсор и уровень записи — 30 раз в секунду, пока что-то звучит. */
    void tick() {
        boolean rec = s.recorder.isRecording();
        long pos = Math.max(0, s.engine.position() - (s.playing() ? s.output.latencyFrames() : 0));
        long len = Math.max(p.lengthFrames(), rec ? s.recordStart() + s.recordedFrames() : 0);
        tvBar.setText(Mix.barBeat(p, pos));
        tvTime.setText(Mix.time(pos) + " / " + Mix.time(len));
        for (Row r : rows) {
            r.view.setPlayhead(pos > 0 || s.playing() ? pos : -1);
            if (rec && s.recordingTrack == r.t) { r.view.setLevel(s.recorder.level()); r.info.setText("● Запись " + Mix.time(s.recordedFrames())); }
        }
    }
    void startTicker() { ui().removeCallbacks(ticker); ui().post(ticker); }
    static final class Ticker implements Runnable {
        final MainActivity a; boolean was;
        Ticker(MainActivity a) { this.a = a; }
        @Override public void run() {
            boolean busy = a.s.playing() || a.s.recorder.isRecording();
            if (busy) a.tick(); else if (was) a.refresh();
            was = busy;
            a.ui().postDelayed(this, busy ? 33 : 250);
        }
    }

    // ================================================================== нажатия
    @Override
    public void onClick(View v) {
        int id = v.getId();
        Object tag = v.getTag();
        Row r = tag instanceof Row ? (Row) tag : null;
        if (id == R.id.btnPlay) { if (s.playing() || s.recorder.isRecording()) s.stopTransport(); else play(); refresh(); }
        else if (id == R.id.btnRewind) { s.rewind(); refresh(); }
        else if (id == R.id.btnTempo) tempoDialog();
        else if (id == R.id.btnMetro) { p.metronome = !p.metronome; s.changed(); refresh(); }
        else if (id == R.id.btnMaster) new FxEditor(this, s, p.master, "Мастер — эффекты всей песни").show();
        else if (id == R.id.btnExport) startExport(false);
        else if (id == R.id.btnAd) watchAd();
        else if (id == R.id.btnPrivacy) openPrivacy();
        else if (id == R.id.btnMenu) showMenu(v);
        else if (id == R.id.btnAddAudio) addTrack(Track.AUDIO);
        else if (id == R.id.btnAddInstr) addTrack(Track.INSTRUMENT);
        else if (r == null) return;
        else if (id == R.id.rowMain) { if (r.t.type == Track.AUDIO) recordClicked(r.t); else openInstrument(r.t); }
        else if (id == R.id.rowView) { if (r.t.type == Track.INSTRUMENT) openInstrument(r.t); }
        else if (id == R.id.rowName) rename(r.t);
        else if (id == R.id.rowMute) { r.t.mute = !r.t.mute; s.changed(); refresh(); }
        else if (id == R.id.rowSolo) { r.t.solo = !r.t.solo; s.changed(); refresh(); }
        else if (id == R.id.rowFx) new FxEditor(this, s, r.t.fx, "Эффекты — " + r.t.name).show();
        else if (id == R.id.rowMore) trackMenu(v, r.t);
    }

    void play() {
        if (p.lengthFrames() == 0) { toast("Пока нечего играть: запишите голос (●) или сыграйте ноты (⌨)"); return; }
        s.play();
        startTicker();
    }
    void addTrack(int type) {
        Track t = s.addTrack(type);
        if (t == null) { toast("Не больше " + Project.MAX_TRACKS + " дорожек"); return; }
        rebuildRows(); refresh();
        if (type == Track.INSTRUMENT) chooseInstrument(t, true);
    }
    void openInstrument(Track t) {
        if (s.recorder.isRecording()) { toast("Сначала остановите запись"); return; }
        startActivity(new Intent(this, InstrumentActivity.class).putExtra("track", t.id));
    }

    void trackMenu(View anchor, Track t) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Переименовать");
        if (t.type == Track.INSTRUMENT) { menu.add(0, 2, 1, "Инструмент: " + Synth.NAMES[t.instrument]); menu.add(0, 3, 2, "Ноты и клавиши"); }
        menu.add(0, 4, 3, "Эффекты");
        menu.add(0, 5, 4, "Выше"); menu.add(0, 6, 5, "Ниже");
        if (t.hasContent()) menu.add(0, 7, 6, t.type == Track.AUDIO ? "Удалить запись" : "Удалить все ноты");
        menu.add(0, 8, 7, "Удалить дорожку");
        m.setOnMenuItemClickListener(new TrackMenu(this, t));
        m.show();
    }
    static final class TrackMenu implements PopupMenu.OnMenuItemClickListener {
        final MainActivity a; final Track t;
        TrackMenu(MainActivity a, Track t) { this.a = a; this.t = t; }
        @Override public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case 1: a.rename(t); break;
                case 2: a.chooseInstrument(t, false); break;
                case 3: a.openInstrument(t); break;
                case 4: new FxEditor(a, a.s, t.fx, "Эффекты — " + t.name).show(); break;
                case 5: a.p.move(t, -1); a.s.changed(); a.rebuildRows(); a.refresh(); break;
                case 6: a.p.move(t, 1); a.s.changed(); a.rebuildRows(); a.refresh(); break;
                case 7: a.confirm(t.type == Track.AUDIO ? "Удалить запись «" + t.name + "»?" : "Удалить все ноты «" + t.name + "»? (можно отменить в редакторе ↶)", "Удалить", new DialogAction(a, DialogAction.CLEAR, t)); break;
                case 8: a.confirm("Удалить дорожку «" + t.name + "» вместе с содержимым?", "Удалить", new DialogAction(a, DialogAction.DELETE, t)); break;
                default: return false;
            }
            return true;
        }
    }
    void chooseInstrument(Track t, boolean thenOpen) {
        DialogAction a = new DialogAction(this, DialogAction.INSTRUMENT, t);
        a.flag = thenOpen;
        new AlertDialog.Builder(this).setTitle("Инструмент").setItems(Synth.NAMES, a).show();
    }
    void setInstrument(Track t, int which, boolean thenOpen) {
        boolean defaultName = false;
        for (String n : Synth.NAMES) if (t.name.startsWith(n) || t.name.startsWith("Инструмент")) defaultName = true;
        t.instrument = which;
        if (defaultName) t.name = Synth.NAMES[which];
        s.engine.panic();
        s.changed();
        refresh();
        if (thenOpen) openInstrument(t);
    }

    void rename(Track t) {
        EditText e = new EditText(this);
        e.setText(t.name);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        DialogAction a = new DialogAction(this, DialogAction.RENAME, t);
        a.edit = e;
        new AlertDialog.Builder(this).setTitle("Название дорожки").setView(e).setPositiveButton("Готово", a).setNegativeButton("Отмена", null).show();
    }

    void tempoDialog() {
        if (s.playing() || s.recorder.isRecording()) { s.stopTransport(); refresh(); }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER);
        NumberPicker bpm = new NumberPicker(this);
        bpm.setMinValue(40); bpm.setMaxValue(240); bpm.setValue(p.bpm);
        NumberPicker beats = new NumberPicker(this);
        beats.setMinValue(2); beats.setMaxValue(7); beats.setValue(p.beats);
        TextView l1 = new TextView(this); l1.setText(" BPM    "); TextView l2 = new TextView(this); l2.setText(" / 4");
        box.addView(bpm); box.addView(l1); box.addView(beats); box.addView(l2);
        DialogAction a = new DialogAction(this, DialogAction.TEMPO, null);
        a.bpm = bpm; a.beats = beats;
        new AlertDialog.Builder(this).setTitle("Темп и размер").setMessage("Ноты идут за темпом, аудиозаписи — нет (они не растягиваются).")
            .setView(box).setPositiveButton("Готово", a).setNegativeButton("Отмена", null).show();
    }

    // ================================================================== запись с микрофона
    void recordClicked(Track t) {
        if (s.recorder.isRecording()) { if (s.recordingTrack == t) s.stopTransport(); else toast("Идёт запись другой дорожки"); refresh(); return; }
        if (exporting) { toast("Подождите окончания экспорта"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecord = t;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (t.frames > 0) confirm("Перезаписать «" + t.name + "»? Запись начнётся с позиции курсора (" + Mix.barBeat(p, s.engine.position()) + "), старая заменится.", "Перезаписать", new DialogAction(this, DialogAction.RECORD, t));
        else beginRecord(t);
    }
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MIC) return;
        Track t = pendingRecord;
        pendingRecord = null;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { if (t != null) recordClicked(t); }
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this).setTitle("Нужен микрофон")
                .setMessage("Без доступа к микрофону записывать голос нельзя. Разрешите его в настройках: Разрешения → Микрофон. Инструменты, эффекты и экспорт работают и без него.")
                .setPositiveButton("Открыть настройки", new DialogAction(this, DialogAction.APP_SETTINGS, null)).setNegativeButton("Не сейчас", null).show();
        } else toast("Без доступа к микрофону запись голоса невозможна. Инструменты работают.");
    }
    void beginRecord(Track t) {
        String err = s.startAudioRecord(t);
        if (err != null) { alert("Запись не началась", err); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startTicker();
        refresh();
    }

    // ================================================================== экспорт
    void startExport(boolean midi) {
        if (s.recorder.isRecording()) { toast("Сначала остановите запись"); return; }
        if (exporting) return;
        if (midi) {
            boolean any = false;
            for (Track t : p.tracks()) if (t.type == Track.INSTRUMENT && t.notes().length > 0) any = true;
            if (!any) { toast("Нет нот для MIDI — экспорт MIDI сохраняет только инструментальные дорожки"); return; }
        } else if (p.lengthFrames() == 0) { toast("Нечего экспортировать"); return; }
        if (s.playing()) s.stopTransport();
        String name = "Симфония_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + (midi ? ".mid" : ".wav");
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(midi ? "audio/midi" : "audio/wav").putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(it, midi ? REQ_MID : REQ_WAV); }
        catch (ActivityNotFoundException e) { alert("Экспорт недоступен", "На телефоне нет системного выбора файлов."); }
    }
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if ((code != REQ_WAV && code != REQ_MID) || result != RESULT_OK || data == null || data.getData() == null) return;
        boolean midi = code == REQ_MID;
        boolean watermark = !midi && ads.available() && s.cleanExports() <= 0;
        s.saveNow();
        exporting = true;
        export = new ExportTask(this, data.getData(), midi, watermark);
        exportBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportBar.setMax(100);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        exportBar.setPadding(pad, pad, pad, pad);
        exportDialog = new AlertDialog.Builder(this).setTitle(midi ? "Сохраняю MIDI…" : "Сведение в WAV…").setView(exportBar).setCancelable(false)
            .setNegativeButton("Отмена", new DialogAction(this, DialogAction.CANCEL_EXPORT, null)).show();
        new Thread(export, "export").start();
        refresh();
    }
    void exportFinished(Uri uri, boolean midi, boolean watermark, String error) {
        exporting = false;
        export = null;
        if (exportDialog != null) { exportDialog.dismiss(); exportDialog = null; }
        if (error != null) {
            try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
            if (!"отменено".equals(error)) alert("Экспорт не удался", error);
            refresh();
            return;
        }
        if (!midi && !watermark && ads.available() && s.cleanExports() > 0) s.setCleanExports(s.cleanExports() - 1);
        DialogAction share = new DialogAction(this, DialogAction.SHARE, null);
        share.uri = uri; share.flag = midi;
        new AlertDialog.Builder(this).setTitle("Готово")
            .setMessage(midi ? "MIDI-файл сохранён: ноты всех инструментов, темп и размер. Откроется в FL Studio, Ableton, GarageBand, MuseScore."
                : watermark ? "Файл сохранён (стерео, 44,1 кГц, 16 бит). В конце — короткая звуковая метка «Симфонии». Посмотрите рекламу, чтобы следующий экспорт был без неё."
                : "Файл сохранён (стерео, 44,1 кГц, 16 бит).")
            .setPositiveButton("Поделиться", share).setNegativeButton("OK", null).show();
        refresh();
    }

    // ================================================================== реклама
    Ads createAds(boolean coldStart) {
        // Gradle-сборка: настоящий SDK Яндекса. Сборка build.sh с DEMO_ADS=1: демо-заглушки. Иначе — без рекламы.
        for (String name : new String[]{"com.domovoi4ik.symphony.ads.YandexAds", "com.domovoi4ik.symphony.ads.DemoAds"}) {
            try {
                Class<?> c = Class.forName(name);
                return (Ads) c.getConstructor(Activity.class, Ads.Callback.class, boolean.class, boolean.class)
                    .newInstance(this, this, s.adConsent() == 1, coldStart);
            } catch (Throwable t) { /* нет такой реализации — пробуем следующую */ }
        }
        return new Ads.None();
    }
    void watchAd() {
        if (s.recorder.isRecording()) { toast("Сначала остановите запись"); return; }
        if (!ads.rewardedReady()) { toast("Реклама недоступна"); ads.preload(); return; }
        ads.showRewarded();
    }
    @Override public void onAdOpening() {
        inAd = true;
        adRewardPending = false;
        if (s.playing()) s.stopTransport();          // реклама со звуком — музыку на паузу
        s.engine.panic();
    }
    @Override public void onAdRewarded() { if (inAd) adRewardPending = true; else grantReward(); }
    @Override public void onAdClosed(boolean rewardedFlow, boolean failed) {
        inAd = false;
        if (adRewardPending) grantReward();
        else if (failed && rewardedFlow) toast("Реклама недоступна");
        refresh();
    }
    @Override public boolean canShowAppOpen() {
        return !isFinishing() && hasWindowFocus() && !s.playing() && !s.recorder.isRecording() && !exporting && !inAd;
    }
    void grantReward() {
        adRewardPending = false;
        s.setCleanExports(s.cleanExports() + 1);
        toast("Награда: экспорт без звуковой метки (в запасе: " + s.cleanExports() + ")");
        refresh();
    }
    void askConsent() {
        new AlertDialog.Builder(this).setTitle("Реклама и данные").setCancelable(false)
            .setMessage("«Симфония» бесплатна и показывает рекламу Яндекса. Рекламный SDK получает рекламный ID, IP-адрес, модель телефона, " +
                "версию Android и язык. Ваши записи и проекты никуда не передаются.\n\nРазрешить подбирать рекламу под ваши интересы? Изменить можно в меню ⋮.")
            .setPositiveButton("Разрешить", new DialogAction(this, DialogAction.CONSENT_YES, null))
            .setNegativeButton("Не разрешать", new DialogAction(this, DialogAction.CONSENT_NO, null))
            .setNeutralButton("Политика", new DialogAction(this, DialogAction.PRIVACY, null)).show();
    }
    void setConsent(boolean on) { s.setAdConsent(on ? 1 : 0); ads.setPersonalized(on); }

    // ================================================================== меню
    void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Экспорт MIDI (ноты)");
        menu.add(0, 2, 1, "Компенсация задержки записи…");
        menu.add(0, 3, 2, "Громкость мастера…");
        menu.add(0, 4, 3, "Конфиденциальность");
        if (ads.available()) menu.add(0, 5, 4, "Персональная реклама").setCheckable(true).setChecked(s.adConsent() == 1);
        menu.add(0, 6, 5, "Новый проект");
        menu.add(0, 7, 6, "О приложении");
        m.setOnMenuItemClickListener(new MainMenu(this));
        m.show();
    }
    static final class MainMenu implements PopupMenu.OnMenuItemClickListener {
        final MainActivity a;
        MainMenu(MainActivity a) { this.a = a; }
        @Override public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case 1: a.startExport(true); break;
                case 2: a.sliderDialog("Компенсация задержки", "Если новая запись отстаёт от остальных дорожек, увеличьте сдвиг: начало записи срежется на это время. Обычно 80–250 мс, в Bluetooth-наушниках больше. Проверка: запишите хлопки под метроном и подберите сдвиг, пока они не совпадут с долями.", 500, a.s.latencyMs(), " мс", DialogAction.LATENCY); break;
                case 3: a.sliderDialog("Громкость мастера", "Общая громкость песни перед лимитером.", 100, a.p.masterVolume, "%", DialogAction.MASTER_VOL); break;
                case 4: a.openPrivacy(); break;
                case 5: a.setConsent(a.s.adConsent() != 1); a.toast(a.s.adConsent() == 1 ? "Персональная реклама включена" : "Персональная реклама выключена"); break;
                case 6: a.confirm("Начать новый проект? Все дорожки, ноты и записи текущего будут удалены. (Сначала можно экспортировать WAV/MIDI.)", "Удалить и начать", new DialogAction(a, DialogAction.NEW_PROJECT, null)); break;
                case 7: a.about(); break;
                default: return false;
            }
            return true;
        }
    }
    void sliderDialog(String title, String msg, int max, int value, String unit, int kind) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView t = new TextView(this); t.setText(msg);
        TextView val = new TextView(this); val.setTextSize(20); val.setPadding(0, pad / 2, 0, 0);
        SeekBar sb = new SeekBar(this); sb.setMax(max); sb.setProgress(value);
        DialogSlider l = new DialogSlider(this, val, unit, kind);
        sb.setOnSeekBarChangeListener(l);
        l.onProgressChanged(sb, value, false);
        box.addView(t); box.addView(val); box.addView(sb);
        new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Готово", null).show();
    }
    void openPrivacy() {
        String url = getString(R.string.privacy_url);
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { alert("Нет браузера", "Политика конфиденциальности:\n" + url); }
    }
    void about() {
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        alert("Симфония " + v, "Мобильная студия: до 8 дорожек — голос с микрофона и инструменты (пианино, синтезаторы, бас, ударные…), " +
            "ноты с экранной или USB/Bluetooth-MIDI-клавиатуры, пиано-ролл, 10 встроенных эффектов, сведение в WAV и экспорт MIDI.\n\n" +
            "Разработчик и владелец: " + getString(R.string.developer) + "\nКонтакт: " + getString(R.string.contact) +
            "\n\nЗаписи и проекты хранятся только на этом телефоне.");
    }
    void newProject() {
        s.stopTransport();
        for (Track t : p.tracks()) s.deleteTrack(t);
        p.master.clear();
        p.bpm = 110; p.beats = 4;
        s.addTrack(Track.AUDIO);
        Track k = s.addTrack(Track.INSTRUMENT);
        if (k != null) k.name = Synth.NAMES[Synth.PIANO];
        s.engine.seek(0);
        s.changed();
        peaks.clear();
        rebuildRows(); refresh();
    }

    // ================================================================== мелочи
    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
    void alert(String title, String msg) { if (!isFinishing()) new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show(); }
    void confirm(String msg, String yes, DialogAction a) { new AlertDialog.Builder(this).setMessage(msg).setPositiveButton(yes, a).setNegativeButton("Отмена", null).show(); }

    // ================================================================== вложенные классы (без лямбд)
    static final class Slider implements SeekBar.OnSeekBarChangeListener {
        final MainActivity a; final Track t; final boolean pan;
        Slider(MainActivity a, Track t, boolean pan) { this.a = a; this.t = t; this.pan = pan; }
        @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
            if (!user) return;
            if (pan) { int pv = v - 100; if (Math.abs(pv) < 6) { pv = 0; } t.pan = pv; } else t.volume = v;   // центр «прилипает»
        }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { if (pan && t.pan == 0) s.setProgress(100); a.s.changed(); a.refresh(); }
    }
    static final class DialogSlider implements SeekBar.OnSeekBarChangeListener {
        final MainActivity a; final TextView val; final String unit; final int kind;
        DialogSlider(MainActivity a, TextView val, String unit, int kind) { this.a = a; this.val = val; this.unit = unit; this.kind = kind; }
        @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
            val.setText(v + unit);
            if (kind == DialogAction.LATENCY) a.s.setLatencyMs(v); else a.p.masterVolume = v;
        }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { a.s.changed(); }
    }
    static final class PeaksTask implements Runnable {
        final MainActivity a; final String name; final File f;
        PeaksTask(MainActivity a, String name, File f) { this.a = a; this.name = name; this.f = f; }
        @Override public void run() {
            float[] pk;
            try { pk = Peaks.compute(f, 800); } catch (IOException e) { pk = new float[0]; }
            a.ui().post(new PeaksDone(a, name, pk));
        }
    }
    static final class PeaksDone implements Runnable {
        final MainActivity a; final String name; final float[] pk;
        PeaksDone(MainActivity a, String name, float[] pk) { this.a = a; this.name = name; this.pk = pk; }
        @Override public void run() { a.peaks.put(name, pk); a.peaksPending.remove(name); if (!a.isDestroyed()) a.refresh(); }
    }

    /** Экспорт WAV/MIDI в фоне, с прогрессом и отменой. */
    static final class ExportTask implements Runnable, Exporter.Progress {
        final MainActivity a; final Uri uri; final boolean midi, watermark;
        volatile boolean cancel;
        ExportTask(MainActivity a, Uri uri, boolean midi, boolean watermark) { this.a = a; this.uri = uri; this.midi = midi; this.watermark = watermark; }
        @Override public void run() {
            String error = null;
            OutputStream out = null;
            try {
                out = a.getContentResolver().openOutputStream(uri, "w");
                if (out == null) throw new IOException("не удалось открыть файл для записи");
                long n = midi ? MidiFile.write(a.p, out) : Exporter.export(a.p, a.s.dir, out, watermark, this);
                if (n == 0) error = "Нечего экспортировать";
            } catch (IOException e) {
                error = "отменено".equals(e.getMessage()) ? "отменено" : "Ошибка записи (нет места?): " + e.getMessage();
            } catch (RuntimeException e) {
                error = "Ошибка: " + e;
            } finally {
                if (out != null) try { out.close(); } catch (IOException e) { if (error == null) error = "Не удалось дописать файл"; }
            }
            a.ui().post(new ExportDone(a, uri, midi, watermark, error));
        }
        @Override public void onProgress(int pct) { a.ui().post(new ExportProgress(a, pct)); }
        @Override public boolean cancelled() { return cancel; }
    }
    static final class ExportProgress implements Runnable {
        final MainActivity a; final int pct;
        ExportProgress(MainActivity a, int pct) { this.a = a; this.pct = pct; }
        @Override public void run() { if (a.exportBar != null) a.exportBar.setProgress(pct); }
    }
    static final class ExportDone implements Runnable {
        final MainActivity a; final Uri uri; final boolean midi, watermark; final String error;
        ExportDone(MainActivity a, Uri uri, boolean midi, boolean watermark, String error) { this.a = a; this.uri = uri; this.midi = midi; this.watermark = watermark; this.error = error; }
        @Override public void run() { if (!a.isDestroyed()) a.exportFinished(uri, midi, watermark, error); }
    }

    static final class DialogAction implements DialogInterface.OnClickListener {
        static final int RECORD = 1, DELETE = 2, CLEAR = 3, RENAME = 4, APP_SETTINGS = 5, CANCEL_EXPORT = 6, SHARE = 7,
            CONSENT_YES = 8, CONSENT_NO = 9, PRIVACY = 10, INSTRUMENT = 11, TEMPO = 12, LATENCY = 13, MASTER_VOL = 14, NEW_PROJECT = 15;
        final MainActivity a; final int kind; final Track t;
        EditText edit; Uri uri; boolean flag; NumberPicker bpm, beats;
        DialogAction(MainActivity a, int kind, Track t) { this.a = a; this.kind = kind; this.t = t; }
        @Override public void onClick(DialogInterface d, int which) {
            switch (kind) {
                case RECORD: a.beginRecord(t); break;
                case DELETE: a.s.deleteTrack(t); a.rebuildRows(); a.refresh(); break;
                case CLEAR: a.s.clearTrack(t); a.refresh(); break;
                case RENAME: { String n = edit.getText().toString().trim(); if (!n.isEmpty()) t.name = n; a.s.changed(); a.refresh(); break; }
                case INSTRUMENT: a.setInstrument(t, which, flag); break;
                case TEMPO: a.p.bpm = bpm.getValue(); a.p.beats = beats.getValue(); a.s.changed(); a.refresh(); break;
                case APP_SETTINGS:
                    try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.getPackageName(), null))); }
                    catch (ActivityNotFoundException ignored) { }
                    break;
                case CANCEL_EXPORT: if (a.export != null) a.export.cancel = true; break;
                case SHARE:
                    try {
                        Intent s = new Intent(Intent.ACTION_SEND).setType(flag ? "audio/midi" : "audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        a.startActivity(Intent.createChooser(s, "Поделиться"));
                    } catch (ActivityNotFoundException ignored) { }
                    break;
                case CONSENT_YES: a.setConsent(true); break;
                case CONSENT_NO: a.setConsent(false); break;
                case PRIVACY: a.openPrivacy(); break;
                case NEW_PROJECT: a.newProject(); break;
                default: break;
            }
        }
    }
}
