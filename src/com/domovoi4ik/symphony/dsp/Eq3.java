package com.domovoi4ik.symphony.dsp;

/** Трёхполосный эквалайзер: низкая полка 150 Гц, середина (частота настраивается), высокая полка 6 кГц. */
public final class Eq3 extends Effect {
    private final Biquad lo = new Biquad(), mid = new Biquad(), hi = new Biquad();
    private float gl, gm, gh;
    public Eq3() { super(Effects.EQ, new float[]{0.5f, 0.5f, 0.5f, 0.5f}); }
    @Override public String name() { return Effects.NAMES[type]; }
    @Override public String[] paramNames() { return new String[]{"Низ", "Середина", "Частота середины", "Верх"}; }
    float g(float t) { return (t - 0.5f) * 30; }
    float midHz() { return expo(200, 5000, p[2]); }
    @Override public String format(int i) { return i == 2 ? hz(midHz()) : dbs(g(p[i == 3 ? 3 : i])); }
    @Override public void reset() { lo.reset(); mid.reset(); hi.reset(); }
    @Override protected void update() {
        gl = g(p[0]); gm = g(p[1]); gh = g(p[3]);
        lo.set(Biquad.LOWSHELF, 150, 0.7f, gl);
        mid.set(Biquad.PEAK, midHz(), 0.9f, gm);
        hi.set(Biquad.HIGHSHELF, 6000, 0.7f, gh);
    }
    @Override protected void run(float[] l, float[] r, int off, int n) {
        if (Math.abs(gl) > 0.05f) lo.run(l, r, off, n);
        if (Math.abs(gm) > 0.05f) mid.run(l, r, off, n);
        if (Math.abs(gh) > 0.05f) hi.run(l, r, off, n);
    }
}
