package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

/** Partículas de luz tenues flotando encima de la carátula (color del tema). */
public class ParticlesView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFFFFB020;
    private boolean corriendo = false, init = false;
    private static final int N = 55;
    private final float[] px = new float[N], py = new float[N], pvx = new float[N], pvy = new float[N], pr = new float[N], pa = new float[N];
    private final Handler h = new Handler();
    private byte[] fft = null;
    private float bass = 0.3f;

    private final Runnable loop = new Runnable() {
        public void run() { if (!corriendo) return; invalidate(); h.postDelayed(this, 45); }
    };

    public ParticlesView(Context c) { super(c); }
    public ParticlesView(Context c, AttributeSet a) { super(c, a); }

    public void setColor(int col) { color = col; }
    public void setFft(byte[] f) { fft = f; }
    public void iniciar() { setVisibility(VISIBLE); if (!corriendo) { corriendo = true; h.post(loop); } }
    public void parar() { corriendo = false; h.removeCallbacks(loop); setVisibility(GONE); }

    private void crear(int w, int ht) {
        for (int i = 0; i < N; i++) {
            px[i] = (float) (Math.random() * w);
            py[i] = (float) (Math.random() * ht);
            pvx[i] = (float) ((Math.random() - 0.5) * 0.8);
            pvy[i] = (float) ((Math.random() - 0.5) * 1.0);
            pr[i] = (float) (1 + Math.random() * 3);
            pa[i] = (float) (Math.random() * 6.28);
        }
        init = true;
    }

    protected void onDraw(Canvas cv) {
        int w = getWidth(), ht = getHeight();
        if (w == 0 || ht == 0) return;
        if (!init) crear(w, ht);
        float target;
        if (fft != null && fft.length > 8) {
            float s = 0; for (int k = 2; k < 16 && k < fft.length; k += 2) { float re = fft[k], im = fft[k + 1]; s += (float) Math.sqrt(re * re + im * im); }
            target = Math.min(1f, s / 8f / 128f * 1.6f);
        } else {
            target = 0.3f + 0.2f * (float) Math.sin(System.currentTimeMillis() / 240.0);
        }
        bass += (target - bass) * 0.3f;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        for (int i = 0; i < N; i++) {
            px[i] += pvx[i] * (1f + bass); py[i] += pvy[i] * (1f + bass); pa[i] += 0.03f;
            if (px[i] < 0) px[i] = w; if (px[i] > w) px[i] = 0;
            if (py[i] < 0) py[i] = ht; if (py[i] > ht) py[i] = 0;
            float al = 0.10f + 0.14f * (float) Math.sin(pa[i]) + bass * 0.35f; if (al < 0) al = 0; if (al > 0.85f) al = 0.85f;
            float rad = pr[i] * (1f + bass * 0.8f);
            paint.setColor(Color.argb((int) (al * 255), r, g, b));
            cv.drawCircle(px[i], py[i], rad, paint);
            paint.setColor(Color.argb((int) (al * 60), r, g, b));
            cv.drawCircle(px[i], py[i], rad * 2.2f, paint);
        }
    }
}
