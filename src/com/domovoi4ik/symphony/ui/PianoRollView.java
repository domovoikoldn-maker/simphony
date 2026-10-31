package com.domovoi4ik.symphony.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.domovoi4ik.symphony.core.Note;
import com.domovoi4ik.symphony.core.Project;
import com.domovoi4ik.symphony.core.Track;
import com.domovoi4ik.symphony.synth.Drums;
import com.domovoi4ik.symphony.synth.Synth;

/**
 * Пиано-ролл: сетка «время × высота», ноты — прямоугольники.
 *   • тап по пустому месту — поставить ноту (длина = сетка или последняя использованная);
 *   • тап по ноте — удалить; тянуть ноту — сдвинуть (по сетке), тянуть за правый край — изменить длину;
 *   • тянуть по пустому месту — прокрутка, щипок двумя пальцами — масштаб по времени;
 *   • тап по линейке сверху — поставить курсор воспроизведения; клавиши слева — прослушать ноту.
 */
public final class PianoRollView extends View {
    public interface Listener {
        void audition(int pitch, boolean on);
        void seek(long tick);
        /** Сейчас изменим ноты — сохранить состояние для «Отменить». */
        void beforeEdit();
        void edited();
    }
    static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    static final int NONE = 0, EMPTY = 1, NOTE = 2, RESIZE = 3, SCROLL = 4, SEEK = 5, KEYS = 6, DRAG = 7;

    private Project p;
    private Track track;
    private Listener listener;
    private final float d;
    private float pxPerTick, scrollTick, topPitch = 84, rowH, keysW, rulerH;
    private long playheadTick = -1;
    private int lastLen;
    // касание
    private int mode = NONE, auditioned = -1;
    private float downX, downY, lastX, lastY;
    private Note grabbed, original;
    private boolean undoPushed, dragged;
    private final ScaleGestureDetector scale;
    private final Paint pRowW = new Paint(), pRowB = new Paint(), pGrid = new Paint(), pBeat = new Paint(), pBar = new Paint(),
        pNote = new Paint(Paint.ANTI_ALIAS_FLAG), pNoteEdge = new Paint(Paint.ANTI_ALIAS_FLAG), pKeyW = new Paint(), pKeyB = new Paint(),
        pText = new Paint(Paint.ANTI_ALIAS_FLAG), pRuler = new Paint(), pHead = new Paint(), pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private int color = 0xFFE8B04B;

    public PianoRollView(Context c) { this(c, null); }
    public PianoRollView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        pxPerTick = 64 * d / Project.PPQ;
        rowH = 18 * d; keysW = 44 * d; rulerH = 24 * d;
        pRowW.setColor(0xFF1E1E26); pRowB.setColor(0xFF17171D);
        pGrid.setColor(0xFF2A2A33); pBeat.setColor(0xFF3A3A46); pBar.setColor(0xFF6A6878);
        pNoteEdge.setColor(0xCCFFFFFF); pNoteEdge.setStyle(Paint.Style.STROKE); pNoteEdge.setStrokeWidth(1.5f * d);
        pKeyW.setColor(0xFFE8E5DF); pKeyB.setColor(0xFF22222A);
        pText.setColor(0xFF55525C); pText.setTextSize(10 * d);
        pSmall.setColor(0xFFB8B5C0); pSmall.setTextSize(10 * d);
        pRuler.setColor(0xFF121216); pHead.setColor(0xFFFFFFFF); pHead.setStrokeWidth(2 * d);
        scale = new ScaleGestureDetector(c, new Zoom(this));
    }

    public void bind(Project p, Track t, int color, Listener l) {
        this.p = p; track = t; this.color = color; listener = l;
        lastLen = p.gridTicks();
        // показать ноты: центр по средней высоте, для ударных — бочка…тарелки
        Note[] ns = t.notes();
        float center;
        if (t.instrument == Synth.DRUMS) center = 44;
        else if (ns.length > 0) { long s = 0; for (Note n : ns) s += n.pitch; center = s / (float) ns.length; }
        else center = 60;
        topPitch = center + 8;
        invalidate();
    }
    public void setPlayheadTick(long t) { if (t != playheadTick) { playheadTick = t; invalidate(); } }
    public void zoom(float k) { pxPerTick = Math.max(8 * d / Project.PPQ, Math.min(400 * d / Project.PPQ, pxPerTick * k)); invalidate(); }
    /** Прокрутить так, чтобы курсор был виден (во время воспроизведения). */
    public void follow(long tick) {
        float x = tickToX(tick);
        if (x > getWidth() - 40 * d || x < keysW) { scrollTick = Math.max(0, tick - (getWidth() - keysW) * 0.15f / pxPerTick); invalidate(); }
    }

    float tickToX(long tick) { return keysW + (tick - scrollTick) * pxPerTick; }
    long xToTick(float x) { return (long) Math.max(0, scrollTick + (x - keysW) / pxPerTick); }
    int yToPitch(float y) { return Math.max(0, Math.min(127, (int) Math.floor(topPitch - (y - rulerH) / rowH) + 1)); }
    float pitchToY(int pitch) { return rulerH + (topPitch - pitch) * rowH; }

    Note noteAt(float x, float y) {
        if (track == null) return null;
        long tick = xToTick(x);
        int pitch = yToPitch(y);
        Note[] ns = track.notes();
        for (int i = ns.length - 1; i >= 0; i--) { Note n = ns[i]; if (n.pitch == pitch && tick >= n.start && tick < n.end()) return n; }
        return null;
    }

    // ================================================================== касания
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (p == null) return false;
        scale.onTouchEvent(e);
        int act = e.getActionMasked();
        if (e.getPointerCount() > 1) {                                // два пальца — только масштаб
            if (mode == DRAG && original != null && grabbed != null) { track.replaceNote(grabbed, original); }
            stopAudition();
            mode = NONE;
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastX = x; downY = lastY = y; undoPushed = false; dragged = false; grabbed = original = null;
                if (y < rulerH) { mode = SEEK; break; }
                if (x < keysW) { mode = KEYS; audition(yToPitch(y)); break; }
                Note n = noteAt(x, y);
                if (n != null) {
                    float x2 = tickToX(n.end());
                    mode = x2 - x < Math.max(12 * d, (x2 - tickToX(n.start)) * 0.25f) ? RESIZE : NOTE;
                    grabbed = original = n;
                    audition(n.pitch);
                } else mode = EMPTY;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                boolean moved = Math.abs(dx) > 8 * d || Math.abs(dy) > 8 * d;
                if (mode == EMPTY && moved) mode = SCROLL;
                if (mode == NOTE && moved) mode = DRAG;
                if (mode == SCROLL) {
                    scrollTick = Math.max(0, scrollTick - (x - lastX) / pxPerTick);
                    topPitch = Math.max(visibleRows() - 1, Math.min(127, topPitch + (y - lastY) / rowH));
                    invalidate();
                } else if (mode == DRAG || mode == RESIZE) {
                    edit(x, y);
                } else if (mode == KEYS) {
                    int pt = yToPitch(y);
                    if (pt != auditioned) { stopAudition(); audition(pt); }
                } else if (mode == SEEK && listener != null) listener.seek(snapTick(xToTick(x)));
                lastX = x; lastY = y;
                break;
            }
            case MotionEvent.ACTION_UP: {
                stopAudition();
                if (mode == SEEK && listener != null) listener.seek(snapTick(xToTick(x)));
                else if ((mode == NOTE || (mode == RESIZE && !dragged)) && grabbed != null) {   // тап по ноте — удалить
                    before(); track.removeNote(grabbed); after();
                } else if (mode == EMPTY) {                             // тап по пустому — новая нота
                    int pitch = yToPitch(y);
                    long tick = p.quantize > 0 ? (xToTick(x) / p.gridTicks()) * p.gridTicks() : xToTick(x);
                    before();
                    track.addNote(new Note((int) tick, lastLen, pitch, 100));
                    after();
                    if (listener != null) { listener.audition(pitch, true); postDelayed(new Off(listener, pitch), 180); }
                } else if (mode == DRAG || mode == RESIZE) after();
                mode = NONE; grabbed = original = null;
                invalidate();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                stopAudition();
                if ((mode == DRAG || mode == RESIZE) && original != null && grabbed != null) track.replaceNote(grabbed, original);
                mode = NONE;
                invalidate();
                break;
            default: break;
        }
        return true;
    }

    void edit(float x, float y) {
        if (grabbed == null || original == null) return;
        if (Math.abs(x - downX) < 6 * d && Math.abs(y - downY) < 6 * d && !dragged) return;
        dragged = true;
        before();
        Note now;
        if (mode == RESIZE) {
            long end = snapTick(xToTick(x) + (p.quantize > 0 ? p.gridTicks() / 2 : 0));
            int len = (int) Math.max(p.quantize > 0 ? p.gridTicks() : 30, end - original.start);
            now = new Note(original.start, len, original.pitch, original.vel);
            lastLen = len;
        } else {
            long dt = xToTick(x) - xToTick(downX);
            int start = (int) Math.max(0, snapTick(original.start + dt));
            int pitch = Math.max(0, Math.min(127, original.pitch + yToPitch(y) - yToPitch(downY)));
            now = new Note(start, original.len, pitch, original.vel);
            if (pitch != grabbed.pitch) { stopAudition(); audition(pitch); }
        }
        if (now.start != grabbed.start || now.len != grabbed.len || now.pitch != grabbed.pitch) {
            track.replaceNote(grabbed, now);
            grabbed = now;
            invalidate();
        }
    }
    long snapTick(long t) { return p.quantize > 0 ? p.snap(t) : t; }
    void before() { if (!undoPushed && listener != null) { listener.beforeEdit(); undoPushed = true; } }
    void after() { if (listener != null) listener.edited(); }
    void audition(int pitch) { auditioned = pitch; if (listener != null) listener.audition(pitch, true); }
    void stopAudition() { if (auditioned >= 0 && listener != null) listener.audition(auditioned, false); auditioned = -1; }
    float visibleRows() { return (getHeight() - rulerH) / rowH; }

    static final class Off implements Runnable {
        final Listener l; final int pitch;
        Off(Listener l, int pitch) { this.l = l; this.pitch = pitch; }
        @Override public void run() { l.audition(pitch, false); }
    }
    static final class Zoom extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        final PianoRollView v;
        Zoom(PianoRollView v) { this.v = v; }
        @Override public boolean onScale(ScaleGestureDetector g) {
            float fx = g.getFocusX();
            long tick = v.xToTick(fx);
            v.zoom(g.getScaleFactor());
            v.scrollTick = Math.max(0, tick - (fx - v.keysW) / v.pxPerTick);
            return true;
        }
    }

    // ================================================================== рисование
    @Override protected void onDraw(Canvas c) {
        if (p == null || track == null) return;
        float w = getWidth(), h = getHeight();
        boolean drums = track.instrument == Synth.DRUMS;
        topPitch = Math.max(visibleRows() - 1, Math.min(127, topPitch));
        // строки
        for (int pitch = (int) Math.ceil(topPitch); pitch >= 0; pitch--) {
            float y = pitchToY(pitch);
            if (y > h) break;
            if (y + rowH < rulerH) continue;
            c.drawRect(keysW, y, w, y + rowH, BLACK[pitch % 12] ? pRowB : pRowW);
            if (pitch % 12 == 0) c.drawRect(keysW, y + rowH - 1, w, y + rowH, pBeat);
        }
        // вертикальная сетка
        int grid = p.quantize > 0 ? p.gridTicks() : Project.PPQ / 4;
        int bar = Project.PPQ * p.beats;
        long t0 = (long) (scrollTick / grid) * grid;
        for (long t = t0; ; t += grid) {
            float x = tickToX(t);
            if (x > w) break;
            if (x < keysW) continue;
            Paint pp = t % bar == 0 ? pBar : (t % Project.PPQ == 0 ? pBeat : pGrid);
            if (pp == pGrid && grid * pxPerTick < 6 * d) continue;
            c.drawRect(x, rulerH, x + (pp == pBar ? 1.5f * d : 1), h, pp);
        }
        // ноты
        for (Note n : track.notes()) {
            float x = tickToX(n.start), x2 = tickToX(n.end());
            if (x2 < keysW || x > w) continue;
            float y = pitchToY(n.pitch);
            if (y > h || y + rowH < rulerH) continue;
            int alpha = 120 + n.vel;
            pNote.setColor((color & 0x00FFFFFF) | (Math.min(255, alpha) << 24));
            rf.set(Math.max(keysW, x) + 1, y + 1, Math.max(x + 3 * d, x2 - 1), y + rowH - 1);
            c.drawRoundRect(rf, 3 * d, 3 * d, pNote);
            if (n == grabbed) c.drawRoundRect(rf, 3 * d, 3 * d, pNoteEdge);
        }
        // клавиши слева
        c.drawRect(0, rulerH, keysW, h, pKeyB);
        for (int pitch = (int) Math.ceil(topPitch); pitch >= 0; pitch--) {
            float y = pitchToY(pitch);
            if (y > h) break;
            if (y + rowH < rulerH) continue;
            boolean blk = BLACK[pitch % 12];
            if (!drums) {
                rf.set(0, Math.max(rulerH, y) + 0.5f, blk ? keysW * 0.62f : keysW - 2, y + rowH - 0.5f);
                c.drawRect(rf, blk ? pKeyB : pKeyW);
                if (pitch % 12 == 0) c.drawText("C" + (pitch / 12 - 1), keysW * 0.66f, y + rowH - 4 * d, pText);
            } else {
                c.drawRect(0, y + rowH - 1, keysW, y + rowH, pBeat);
                String s = Drums.label(pitch);
                c.drawText(s.length() > 7 ? s.substring(0, 7) : s, 3 * d, y + rowH - 5 * d, pSmall);
            }
            if (pitch == auditioned) { pNote.setColor(color); c.drawRect(keysW - 4 * d, y, keysW, y + rowH, pNote); }
        }
        // линейка тактов
        c.drawRect(0, 0, w, rulerH, pRuler);
        for (long t = (long) (scrollTick / bar) * bar; ; t += bar) {
            float x = tickToX(t);
            if (x > w) break;
            if (x < keysW - 1) continue;
            c.drawRect(x, rulerH * 0.45f, x + 1, rulerH, pBar);
            c.drawText(String.valueOf(t / bar + 1), x + 4 * d, rulerH * 0.7f, pSmall);
        }
        // курсор
        if (playheadTick >= 0) {
            float x = tickToX(playheadTick);
            if (x >= keysW && x <= w) c.drawLine(x, 0, x, h, pHead);
        }
    }
}
