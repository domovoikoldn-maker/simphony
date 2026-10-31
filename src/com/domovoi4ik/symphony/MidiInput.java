package com.domovoi4ik.symphony;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Внешние MIDI-клавиатуры (USB, а также Bluetooth-MIDI, уже подключённые в системе) через android.media.midi.
 * Подключается ко всем устройствам сразу и к новым на лету; ноты отдаёт в Sink.
 * Разрешений не требует. Нет поддержки MIDI в телефоне — просто молчит.
 */
public final class MidiInput {
    public interface Sink {
        /** Из MIDI-потока! vel 0 — отпускание. */
        void onMidiNote(int pitch, int vel);
        /** На главном потоке: список устройств изменился. */
        void onMidiDevices(String names);
    }

    final Context ctx;
    final Sink sink;
    final Handler ui;
    MidiManager mm;
    final ArrayList<MidiDevice> devices = new ArrayList<MidiDevice>();
    final ArrayList<MidiOutputPort> ports = new ArrayList<MidiOutputPort>();
    final ArrayList<String> names = new ArrayList<String>();
    Callback callback;
    boolean running;

    public MidiInput(Context c, Sink sink, Handler ui) { this.ctx = c; this.sink = sink; this.ui = ui; }

    public static boolean supported(Context c) { return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI); }

    @SuppressWarnings("deprecation")
    public void start() {
        if (running || !supported(ctx)) return;
        try {
            mm = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
            if (mm == null) return;
            running = true;
            callback = new Callback(this);
            mm.registerDeviceCallback(callback, ui);
            for (MidiDeviceInfo info : mm.getDevices()) open(info);
        } catch (RuntimeException e) {
            running = false;
        }
    }

    void open(MidiDeviceInfo info) {
        if (!running || info.getOutputPortCount() == 0) return;     // нам нужны устройства, которые ОТДАЮТ ноты
        try { mm.openDevice(info, new Opened(this, info), ui); } catch (RuntimeException ignored) { }
    }

    void opened(MidiDevice dev, MidiDeviceInfo info) {
        if (dev == null) return;
        if (!running) { closeQuiet(dev); return; }
        devices.add(dev);
        for (int i = 0; i < info.getOutputPortCount(); i++) {
            MidiOutputPort port = dev.openOutputPort(i);
            if (port == null) continue;
            port.connect(new Parser(sink));
            ports.add(port);
        }
        Bundle b = info.getProperties();
        String n = b.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (n == null) n = b.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        names.add(n == null ? "MIDI-устройство" : n);
        sink.onMidiDevices(join());
    }

    void removed(MidiDeviceInfo info) {
        for (int i = devices.size() - 1; i >= 0; i--) {
            if (devices.get(i).getInfo().getId() == info.getId()) { closeQuiet(devices.remove(i)); if (i < names.size()) names.remove(i); }
        }
        sink.onMidiDevices(join());
    }

    String join() {
        StringBuilder s = new StringBuilder();
        for (String n : names) { if (s.length() > 0) s.append(", "); s.append(n); }
        return s.toString();
    }

    public void stop() {
        running = false;
        try { if (mm != null && callback != null) mm.unregisterDeviceCallback(callback); } catch (RuntimeException ignored) { }
        for (MidiOutputPort p : ports) try { p.close(); } catch (IOException ignored) { }
        for (MidiDevice dv : devices) closeQuiet(dv);
        ports.clear(); devices.clear(); names.clear();
    }
    static void closeQuiet(MidiDevice d) { try { d.close(); } catch (IOException ignored) { } }

    static final class Opened implements MidiManager.OnDeviceOpenedListener {
        final MidiInput m; final MidiDeviceInfo info;
        Opened(MidiInput m, MidiDeviceInfo info) { this.m = m; this.info = info; }
        @Override public void onDeviceOpened(MidiDevice device) { m.opened(device, info); }
    }
    static final class Callback extends MidiManager.DeviceCallback {
        final MidiInput m;
        Callback(MidiInput m) { this.m = m; }
        @Override public void onDeviceAdded(MidiDeviceInfo info) { m.open(info); }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) { m.removed(info); }
    }

    /** Разбор потока MIDI: note on/off с «бегущим статусом», остальное игнорируем. */
    static final class Parser extends MidiReceiver {
        final Sink sink;
        int status, d1 = -1;
        Parser(Sink s) { sink = s; }
        @Override public void onSend(byte[] msg, int off, int count, long ts) {
            for (int i = off; i < off + count; i++) {
                int b = msg[i] & 0xFF;
                if (b >= 0xF8) continue;                         // такт/активное чувство — пропуск
                if (b >= 0x80) { status = b; d1 = -1; continue; }
                int kind = status & 0xF0;
                if (kind != 0x90 && kind != 0x80) continue;
                if (d1 < 0) { d1 = b; continue; }
                int vel = kind == 0x80 ? 0 : b;
                sink.onMidiNote(d1, vel);
                d1 = -1;
            }
        }
    }
}
