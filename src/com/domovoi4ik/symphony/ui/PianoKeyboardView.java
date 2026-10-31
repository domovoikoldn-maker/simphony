package com.domovoi4ik.symphony.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.domovoi4ik.symphony.synth.Drums;

/**
 * Экранная MIDI-клавиатура: мультитач (аккорды), глиссандо (ведёшь пальцем — ноты меняются),
 * сила нажатия — по высоте касания (ближе к низу клавиши — громче). Подсвечивает и ноты с внешней MIDI-клавиатуры.
 */
public final class PianoKeyboardView extends View {
    public interface Listener { void onKeyDown(int pitch, int vel); void onKeyUp(int pitch); }
    static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    static final String[] NAMES = {"До", "", "Ре", "", "Ми", "Фа", "", "Соль", "", "Ля", "", "Си"};

    private Listener listener;
    private int base = 48;                 // первая (самая левая) нота — до малой октавы
    private int whiteCount = 14;
    private boolean drums;
    private final int[] pointerNote = new int[16];
    private final boolean[] down = new boolean[128], external = new boolean[128];
    private final Paint white = new Paint(), black = new Paint(), line = new Paint(), hl = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final float d;

    public PianoKeyboardView(Context c) { this(c, null); }
    public PianoKeyboardView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        white.setColor(0xFFF3F0EA); black.setColor(0xFF1A1A20); line.setColor(0xFF8A8790); hl.setColor(0xFFE8B04B);
        txt.setColor(0xFF6B6870); txt.setTextSize(11 * d); txt.setTextAlign(Paint.Align.CENTER);
        small.setColor(0xFF6B6870); small.setTextSize(9 * d); small.setTextAlign(Paint.Align.CENTER);
        java.util.Arrays.fill(pointerNote, -1);
        setHapticFeedbackEnabled(true);
    }
    public void setListener(Listener l) { listener = l; }
    public void setDrums(boolean on) { drums = on; invalidate(); }
    public int base() { return base; }
    /** Сдвиг на октаву (±12) — отпускает зажатые ноты, чтобы не «залипли». */
    public void shiftOctave(int dir) { releaseAll(); base = Math.max(12, Math.min(96, base + 12 * dir)); invalidate(); }
    public String rangeLabel() { return octaveName(base) + " – " + octaveName(lastNote()); }
    public void setExternal(int pitch, boolean on) { if (pitch >= 0 && pitch < 128) { external[pitch] = on; postInvalidate(); } }

    int lastNote() { int n = base, w = 0; while (true) { if (!BLACK[n % 12]) { w++; if (w == whiteCount) return n; } n++; } }
    static String octaveName(int n) { return "C" + (n / 12 - 1); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        whiteCount = Math.max(7, Math.min(29, Math.round(w / (46 * d))));
    }

    /** Нота под точкой (чёрные клавиши — сверху). */
    int noteAt(float x, float y) {
        float ww = getWidth() / (float) whiteCount, bh = getHeight() * 0.6f;
        int wi = (int) (x / ww);
        if (wi < 0 || wi >= whiteCount) return -1;
        int note = whiteNote(wi);
        if (y < bh) {
            float fx = x - wi * ww;
            if (fx > ww * 0.68f && BLACK[(note + 1) % 12]) return note + 1;
            if (fx < ww * 0.32f && wi > 0 && BLACK[(note + 11) % 12]) return note - 1;
        }
        return note;
    }
    int whiteNote(int idx) { int n = base, w = -1; while (true) { if (!BLACK[n % 12]) { w++; if (w == idx) return n; } n++; } }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                press(id, e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= pointerNote.length) continue;
                    int n = noteAt(e.getX(i), e.getY(i));
                    if (n != pointerNote[id]) { lift(id); press(id, e.getX(i), e.getY(i)); }
                }
                break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: lift(e.getPointerId(e.getActionIndex())); break;
            case MotionEvent.ACTION_CANCEL: releaseAll(); break;
            default: break;
        }
        return true;
    }
    void press(int id, float x, float y) {
        if (id >= pointerNote.length) return;
        int n = noteAt(x, y);
        if (n < 0 || n > 127) return;
        float keyH = BLACK[n % 12] ? getHeight() * 0.6f : getHeight();
        int vel = (int) (55 + 72 * Math.max(0, Math.min(1, y / keyH)));
        pointerNote[id] = n;
        down[n] = true;
        if (listener != null) listener.onKeyDown(n, vel);
        invalidate();
    }
    void lift(int id) {
        if (id >= pointerNote.length) return;
        int n = pointerNote[id];
        if (n < 0) return;
        pointerNote[id] = -1;
        boolean still = false;
        for (int p : pointerNote) if (p == n) still = true;
        if (!still) { down[n] = false; if (listener != null) listener.onKeyUp(n); }
        invalidate();
    }
    public void releaseAll() { for (int i = 0; i < pointerNote.length; i++) lift(i); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), ww = w / whiteCount, bh = h * 0.6f, bw = ww * 0.62f;
        for (int i = 0; i < whiteCount; i++) {
            int n = whiteNote(i);
            float x = i * ww;
            rf.set(x + 1, 0, x + ww - 1, h - 1);
            c.drawRoundRect(rf, 4 * d, 4 * d, down[n] || external[n] ? hl : white);
            if (drums) {
                String s = Drums.label(n);
                c.drawText(s.length() > 7 ? s.substring(0, 7) : s, x + ww / 2, h - 10 * d, small);
            } else if (n % 12 == 0) c.drawText(octaveName(n), x + ww / 2, h - 10 * d, txt);
        }
        for (int i = 0; i < whiteCount; i++) {
            int n = whiteNote(i);
            if (i < whiteCount - 1 && BLACK[(n + 1) % 12]) {
                float x = (i + 1) * ww - bw / 2;
                rf.set(x, 0, x + bw, bh);
                c.drawRoundRect(rf, 3 * d, 3 * d, down[n + 1] || external[n + 1] ? hl : black);
            }
        }
    }
}
