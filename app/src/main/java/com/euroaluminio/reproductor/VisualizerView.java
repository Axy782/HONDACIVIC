package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

/**
 * Animación de música: ondas (barras) o círculos (anillos).
 * Si recibe datos del audio (Visualizer) se mueve con la música;
 * si no, anima por tiempo (para radios viejos donde el Visualizer no sirve).
 */
public class VisualizerView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFFFFB020;
    private int tipo = 0;               // 0 = ondas, 1 = círculos
    private boolean corriendo = false;
    private byte[] fft = null;
    private final float[] vals = new float[48];
    private final Handler h = new Handler();
    private final long t0 = System.currentTimeMillis();

    private final Runnable loop = new Runnable() {
        public void run() {
            if (!corriendo) return;
            paso();
            invalidate();
            h.postDelayed(this, 45);
        }
    };

    public VisualizerView(Context c) { super(c); }
    public VisualizerView(Context c, AttributeSet a) { super(c, a); }

    public void setColor(int col) { color = col; }
    public void setTipo(int t) { tipo = t; }
    public void setFft(byte[] f) { fft = f; }

    public void iniciar() { if (!corriendo) { corriendo = true; h.post(loop); } }
    public void parar() { corriendo = false; h.removeCallbacks(loop); }

    private void paso() {
        int n = vals.length;
        if (fft != null && fft.length > 8) {
            for (int i = 0; i < n; i++) {
                int idx = 2 + i * 2;
                float mag = 0;
                if (idx + 1 < fft.length) { float re = fft[idx], im = fft[idx + 1]; mag = (float) Math.sqrt(re * re + im * im) / 128f; }
                float target = Math.min(1f, mag * 1.7f);
                vals[i] += (target - vals[i]) * 0.35f;
            }
        } else {
            double tt = (System.currentTimeMillis() - t0) / 220.0;
            for (int i = 0; i < n; i++) {
                float target = (float) (0.26 + 0.34 * (Math.sin(tt + i * 0.4) * 0.5 + 0.5));
                vals[i] += (target - vals[i]) * 0.2f;
            }
        }
    }

    protected void onDraw(Canvas cv) {
        int w = getWidth(), ht = getHeight(); int n = vals.length;
        paint.setColor(color);
        if (tipo == 1) {
            float cx = w / 2f, cy = ht / 2f, base = Math.min(w, ht) * 0.16f;
            float bass = 0; for (int i = 0; i < 6; i++) bass += vals[i]; bass /= 6f;
            paint.setStyle(Paint.Style.STROKE);
            for (int r = 0; r < 4; r++) {
                paint.setAlpha((int) ((0.55f - r * 0.11f) * 255));
                paint.setStrokeWidth(4 + bass * 12);
                cv.drawCircle(cx, cy, base + r * base * 0.6f + bass * 70, paint);
            }
            paint.setAlpha(255);
        } else {
            paint.setStyle(Paint.Style.FILL);
            float bw = (float) w / n;
            paint.setAlpha(220);
            for (int i = 0; i < n; i++) {
                float bh = vals[i] * ht * 0.72f;
                cv.drawRect(i * bw + 2, ht - bh, (i + 1) * bw - 2, ht, paint);
            }
            paint.setAlpha(255);
        }
    }
}
