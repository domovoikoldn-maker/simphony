package com.domovoi4ik.symphony;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.domovoi4ik.symphony.dsp.Effect;
import com.domovoi4ik.symphony.dsp.Effects;
import com.domovoi4ik.symphony.dsp.FxChain;

/**
 * Окно «Эффекты»: цепочка «плагинов» дорожки или мастера. Добавить, включить/выключить, переставить,
 * удалить, крутить ручки — всё слышно сразу, во время воспроизведения.
 */
final class FxEditor {
    final Activity a;
    final Studio s;
    final FxChain chain;
    final String title;
    final float dp;
    AlertDialog dlg;
    LinearLayout box;

    FxEditor(Activity a, Studio s, FxChain chain, String title) {
        this.a = a; this.s = s; this.chain = chain; this.title = title;
        dp = a.getResources().getDisplayMetrics().density;
    }

    void show() {
        ScrollView sv = new ScrollView(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(16);
        box.setPadding(pad, px(4), pad, px(8));
        sv.addView(box);
        build();
        dlg = new AlertDialog.Builder(a).setTitle(title).setView(sv)
            .setPositiveButton("Готово", null).setNeutralButton("+ Эффект", null).create();
        dlg.show();
        // «+ Эффект» не закрывает окно
        dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new Click(this, Click.ADD, null));
    }

    int px(float v) { return Math.round(v * dp); }

    void build() {
        box.removeAllViews();
        Effect[] list = chain.list();
        if (list.length == 0) {
            TextView t = new TextView(a);
            t.setText("Эффектов пока нет. Нажмите «+ Эффект»: эквалайзер, компрессор, реверберация, дилей, хорус, перегруз, фильтр, тремоло, лоу-фай, стерео-расширение.");
            t.setTextColor(0xFF8D8A99);
            t.setPadding(0, px(8), 0, px(8));
            box.addView(t);
            return;
        }
        for (int k = 0; k < list.length; k++) {
            Effect e = list[k];
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.panel);
            card.setPadding(px(12), px(6), px(8), px(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(10);
            box.addView(card, lp);

            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(a);
            name.setText((k + 1) + ". " + e.name());
            name.setTextColor(0xFFEDEAF2);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Switch on = new Switch(a);
            on.setChecked(e.on);
            on.setOnCheckedChangeListener(new Toggle(this, e));
            head.addView(on);
            head.addView(small("↑", new Click(this, Click.UP, e), k > 0));
            head.addView(small("↓", new Click(this, Click.DOWN, e), k < list.length - 1));
            head.addView(small("✕", new Click(this, Click.REMOVE, e), true));
            card.addView(head);

            String[] pn = e.paramNames();
            for (int i = 0; i < pn.length; i++) {
                TextView lbl = new TextView(a);
                lbl.setTextColor(0xFFB8B5C0);
                lbl.setTextSize(13);
                lbl.setPadding(0, px(6), 0, 0);
                card.addView(lbl);
                SeekBar sb = new SeekBar(a);
                sb.setMax(1000);
                sb.setProgress(Math.round(e.p[i] * 1000));
                Param pl = new Param(this, e, i, lbl, pn[i]);
                sb.setOnSeekBarChangeListener(pl);
                pl.label();
                card.addView(sb);
            }
        }
    }

    Button small(String t, View.OnClickListener l, boolean enabled) {
        Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setText(t);
        b.setTextColor(0xFFEDEAF2);
        b.setMinWidth(px(40)); b.setMinimumWidth(px(40)); b.setPadding(0, 0, 0, 0);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1 : 0.3f);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(40), px(40)));
        return b;
    }

    void pickNew() {
        if (chain.size() >= FxChain.MAX) { android.widget.Toast.makeText(a, "Не больше " + FxChain.MAX + " эффектов в цепочке", android.widget.Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[Effects.NAMES.length];
        for (int i = 0; i < items.length; i++) items[i] = Effects.NAMES[i] + " — " + Effects.HINTS[i];
        new AlertDialog.Builder(a).setTitle("Добавить эффект").setItems(items, new Pick(this)).show();
    }

    static final class Click implements View.OnClickListener {
        static final int ADD = 0, UP = 1, DOWN = 2, REMOVE = 3;
        final FxEditor f; final int kind; final Effect e;
        Click(FxEditor f, int kind, Effect e) { this.f = f; this.kind = kind; this.e = e; }
        @Override public void onClick(View v) {
            switch (kind) {
                case ADD: f.pickNew(); return;
                case UP: f.chain.move(e, -1); break;
                case DOWN: f.chain.move(e, 1); break;
                default: f.chain.remove(e); break;
            }
            f.s.changed();
            f.build();
        }
    }
    static final class Pick implements DialogInterface.OnClickListener {
        final FxEditor f;
        Pick(FxEditor f) { this.f = f; }
        @Override public void onClick(DialogInterface d, int which) {
            Effect e = Effects.create(which);
            if (e != null && f.chain.add(e)) { f.s.changed(); f.build(); }
        }
    }
    static final class Toggle implements CompoundButton.OnCheckedChangeListener {
        final FxEditor f; final Effect e;
        Toggle(FxEditor f, Effect e) { this.f = f; this.e = e; }
        @Override public void onCheckedChanged(CompoundButton b, boolean on) { e.on = on; if (!on) e.reset(); f.s.changed(); }
    }
    static final class Param implements SeekBar.OnSeekBarChangeListener {
        final FxEditor f; final Effect e; final int i; final TextView lbl; final String name;
        Param(FxEditor f, Effect e, int i, TextView lbl, String name) { this.f = f; this.e = e; this.i = i; this.lbl = lbl; this.name = name; }
        void label() { lbl.setText(name + ":  " + e.format(i)); }
        @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) { e.p[i] = v / 1000f; label(); } }
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { f.s.changed(); }
    }
}
