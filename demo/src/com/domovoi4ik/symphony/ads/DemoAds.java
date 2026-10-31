/*
 * «Симфония» — ДЕМО-реклама для сборки без SDK Яндекса (./build.sh с DEMO_ADS=1).
 * Разработчик и владелец: domovoi4ik.
 *
 * Показывает, ГДЕ и КАК появляется реклама, и проверяет всю логику приложения
 * (пауза звука, награда только из onRewarded, «Реклама недоступна», закрытие раньше времени):
 *   • при холодном старте — полноэкранная заглушка вместо App Open (закрыть можно через 3 с);
 *   • по кнопке награды — заглушка с отсчётом 10 с; закрыли раньше — награды нет;
 *   • каждый 4-й запрос — «не загрузилась», чтобы было видно сообщение «Реклама недоступна».
 * Это НЕ реклама Яндекса и денег не приносит — на экране так и написано. Для магазина — Gradle-сборка.
 */
package com.domovoi4ik.symphony.ads;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DemoAds implements Ads {
    final Activity act;
    final Callback cb;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean ready, loading, destroyed;
    int requests;

    public DemoAds(Activity act, Callback cb, boolean personalized, boolean coldStart) {
        this.act = act;
        this.cb = cb;
        preload();
        if (coldStart) h.postDelayed(new Step(this, Step.APP_OPEN, null), 1200);
    }

    @Override public boolean available() { return true; }
    @Override public boolean rewardedReady() { return ready && !destroyed; }
    @Override public void preload() {
        if (ready || loading || destroyed) return;
        loading = true;
        h.postDelayed(new Step(this, Step.LOADED, null), 1500);       // «загрузка» 1,5 с
    }
    @Override public void showRewarded() {
        if (!ready) { cb.onAdClosed(true, true); return; }
        ready = false;
        cb.onAdOpening();
        new Screen(this, true).show();
    }
    @Override public void setPersonalized(boolean on) { }
    @Override public void destroy() { destroyed = true; h.removeCallbacksAndMessages(null); }

    void loaded() {
        loading = false;
        if (destroyed) return;
        requests++;
        ready = requests % 4 != 0;              // каждая 4-я «не загрузилась» — показать сценарий ошибки
    }
    int openTries;
    void appOpen() {
        if (destroyed || act.isFinishing()) return;
        if (!cb.canShowAppOpen()) {          // демо: подождать, пока закроют окно согласия (до 30 с)
            if (++openTries < 30) h.postDelayed(new Step(this, Step.APP_OPEN, null), 1000);
            return;
        }
        cb.onAdOpening();
        new Screen(this, false).show();
    }

    /** Полноэкранное окно-заглушка. */
    static final class Screen {
        final DemoAds a; final boolean rewarded;
        Dialog d; TextView timer; Button close;
        int left; boolean rewardGiven;
        Screen(DemoAds a, boolean rewarded) { this.a = a; this.rewarded = rewarded; left = rewarded ? 10 : 3; }

        void show() {
            Activity act = a.act;
            float dp = act.getResources().getDisplayMetrics().density;
            d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int pad = (int) (28 * dp);
            box.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2B2140, 0xFF0E0C14});
            box.setBackground(bg);

            TextView badge = text(act, "ДЕМО · реклама не настоящая", 13, 0xFFE8B04B, false);
            TextView title = text(act, rewarded ? "Здесь будет реклама\nза вознаграждение" : "Здесь будет реклама\nпри открытии приложения", 26, Color.WHITE, true);
            TextView info = text(act, rewarded
                ? "В сборке с SDK Яндекса (блок R-M-20314265-2) вместо этого окна — видеореклама Яндекса.\nДосмотрите до конца — получите экспорт без звуковой метки. Закроете раньше — награды не будет."
                : "В сборке с SDK Яндекса (блок R-M-20314265-3) вместо этого окна — реклама при открытии.\nПоказывается один раз при запуске приложения.", 15, 0xFFB8B5C0, false);
            timer = text(act, "", 18, 0xFFE8B04B, true);
            close = new Button(act);
            close.setText("✕ Закрыть");
            close.setOnClickListener(new Close(this));
            box.addView(badge); box.addView(title); box.addView(info); box.addView(timer); box.addView(close);
            for (int i = 0; i < box.getChildCount(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = (int) (14 * dp);
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                box.getChildAt(i).setLayoutParams(lp);
            }
            d.setContentView(box);
            d.setCancelable(false);
            d.setOnKeyListener(new Back(this));
            Window w = d.getWindow();
            if (w != null) w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            d.show();
            tick();
        }
        static TextView text(Activity act, String s, float size, int color, boolean bold) {
            TextView t = new TextView(act);
            t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }
        void tick() {
            if (a.destroyed || d == null) return;
            if (left > 0) {
                timer.setText(rewarded ? "Награда через " + left + " с" : "Закрыть можно через " + left + " с");
                close.setVisibility(rewarded ? View.VISIBLE : View.INVISIBLE);
                left--;
                a.h.postDelayed(new Step(a, Step.TICK, this), 1000);
            } else {
                if (rewarded && !rewardGiven) { rewardGiven = true; a.cb.onAdRewarded(); }   // как onRewarded()
                timer.setText(rewarded ? "✓ Награда получена" : "");
                close.setVisibility(View.VISIBLE);
            }
        }
        void dismiss() {
            if (d == null) return;
            d.dismiss();
            d = null;
            a.cb.onAdClosed(rewarded, false);                                           // как onAdDismissed()
            if (rewarded) a.preload();
        }
    }

    static final class Step implements Runnable {
        static final int LOADED = 0, APP_OPEN = 1, TICK = 2;
        final DemoAds a; final int kind; final Screen s;
        Step(DemoAds a, int kind, Screen s) { this.a = a; this.kind = kind; this.s = s; }
        @Override public void run() {
            if (kind == LOADED) a.loaded(); else if (kind == APP_OPEN) a.appOpen(); else s.tick();
        }
    }
    static final class Close implements View.OnClickListener {
        final Screen s; Close(Screen s) { this.s = s; }
        @Override public void onClick(View v) { s.dismiss(); }
    }
    static final class Back implements DialogInterface.OnKeyListener {
        final Screen s; Back(Screen s) { this.s = s; }
        @Override public boolean onKey(DialogInterface d, int code, android.view.KeyEvent e) {
            if (code == android.view.KeyEvent.KEYCODE_BACK && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (s.rewarded || s.left <= 0) s.dismiss();               // App Open — только после отсчёта
            }
            return code == android.view.KeyEvent.KEYCODE_BACK;
        }
    }
}
