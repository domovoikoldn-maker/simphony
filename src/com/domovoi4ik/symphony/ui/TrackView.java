package com.domovoi4ik.symphony.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.domovoi4ik.symphony.core.Note;
import com.domovoi4ik.symphony.core.Project;
import com.domovoi4ik.symphony.core.Track;

/**
 * Мини-вид дорожки на главном экране: волна записи (аудио) или «ноты» (инструмент).
 * Все дорожки в одном масштабе — длина проекта, поэтому одинаковые моменты стоят друг под другом.
 */
public final class TrackView extends View {
    private Track track;
    private Project project;
    private float[] peaks;
    private long peaksFrames, playhead = -1, viewFrames = 1;
    private int color = 0xFFE8B04B;
    private String label = "";
    private float level = -1;
    private final Paint bg = new Paint(), fg = new Paint(Paint.ANTI_ALIAS_FLAG), head = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint();
    private final float d;

    public TrackView(Context c) { this(c, null); }
    public TrackView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        bg.setColor(0xFF15151A); bar.setColor(0xFF24242C);
        head.setColor(0xFFFFFFFF); head.setStrokeWidth(2 * d);
        text.setColor(0xFF8D8A99); text.setTextSize(12 * d);
    }
    public void bind(Project p, Track t, int color) { project = p; track = t; this.color = color; invalidate(); }
    public void setPeaks(float[] p, long frames) { peaks = p; peaksFrames = frames; invalidate(); }
    public void setView(long frames) { viewFrames = Math.max(1, frames); invalidate(); }
    public void setPlayhead(long f) { if (f != playhead) { playhead = f; invalidate(); } }
    public void setLabel(String s) { label = s == null ? "" : s; invalidate(); }
    /** Индикатор уровня при записи (−1 — выкл). */
    public void setLevel(float l) { level = l; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        if (project != null) {                     // такты
            long fb = Math.max(1, project.framesPerBar());
            float step = w * fb / (float) viewFrames;
            if (step > 6 * d) for (float x = step; x < w; x += step) c.drawRect(x, 0, x + 1, h, bar);
        }
        boolean muted = track != null && track.mute;
        fg.setColor(muted ? 0xFF4A4852 : color);
        if (track != null && track.type == Track.AUDIO && peaks != null && peaksFrames > 0) {
            float x0 = w * track.offset / (float) viewFrames, span = w * peaksFrames / (float) viewFrames;
            int n = peaks.length;
            float stp = span / n;
            for (int i = 0; i < n; i++) {
                float a = Math.max(0.02f, peaks[i]) * (h / 2 - 2), x = x0 + i * stp;
                if (x > w) break;
                c.drawRect(x, h / 2 - a, x + Math.max(1, stp - 0.4f), h / 2 + a, fg);
            }
        } else if (track != null && track.type == Track.INSTRUMENT && project != null) {
            Note[] ns = track.notes();
            if (ns.length > 0) {
                int lo = 127, hi = 0;
                for (Note n : ns) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
                lo -= 2; hi += 2;
                float rowH = Math.max(2 * d, Math.min(6 * d, (h - 4) / (hi - lo + 1)));
                float span = (hi - lo + 1) * rowH, top = (h - span) / 2;
                for (Note n : ns) {
                    float x = w * project.tickToFrame(n.start) / (float) viewFrames;
                    float x2 = w * project.tickToFrame(n.end()) / (float) viewFrames;
                    if (x > w) continue;
                    float y = top + (hi - n.pitch) * rowH;
                    c.drawRect(x, y, Math.max(x + 2, x2 - 1), y + rowH - 1, fg);
                }
            }
        }
        if (level >= 0) {
            fg.setColor(0xFFE5484D);
            c.drawRect(6 * d, h - 8 * d, 6 * d + Math.min(1, level * 1.4f) * (w - 12 * d), h - 4 * d, fg);
        }
        if (label.length() > 0) c.drawText(label, 8 * d, h / 2 + 4 * d, text);
        if (playhead >= 0) { float x = w * Math.min(1f, playhead / (float) viewFrames); c.drawLine(x, 0, x, h, head); }
    }
}
