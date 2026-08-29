package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Disco de vinilo que gira, con la carátula metida en el centro (como un tocadiscos).
 * Si la canción no tiene carátula, muestra un disco genérico. Ligero: arma el disco
 * UNA vez en un bitmap y luego solo lo rota (barato para el radio viejo).
 */
public class DiscoView extends View {
    private Bitmap discoBmp;          // disco ya compuesto (vinilo + surcos + carátula)
    private Bitmap coverActual;
    private float angulo = 0f;
    private boolean activo = false, sonando = false;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int lastSize = 0;

    public DiscoView(Context c) { super(c); ini(); }
    public DiscoView(Context c, AttributeSet a) { super(c, a); ini(); }
    private void ini() {
        try { setLayerType(LAYER_TYPE_SOFTWARE, null); } catch (Exception e) {}   // dibujo por software: el giro+bitmap no falla en Android viejo
        try { setWillNotDraw(false); } catch (Exception e) {}
    }

    public void setCover(Bitmap cover) { coverActual = cover; discoBmp = null; lastSize = 0; if (activo) invalidate(); }
    public void setActivo(boolean a) { activo = a; setVisibility(a ? VISIBLE : GONE); if (a) invalidate(); }
    public void setSonando(boolean s) { boolean antes = sonando; sonando = s; if (activo && s && !antes) invalidate(); }

    private void construirDisco(int size) {
        try {
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            float cx = size / 2f, cy = size / 2f, r = size / 2f - 2;
            // Vinilo negro
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF0B0B0D);
            cv.drawCircle(cx, cy, r, p);
            // Surcos (círculos gris tenue)
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, size * 0.004f)); p.setColor(0x20FFFFFF);
            for (float rr = r * 0.40f; rr < r * 0.98f; rr += size * 0.020f) cv.drawCircle(cx, cy, rr, p);
            // Reflejo/brillo sutil (un arco claro)
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(size * 0.05f); p.setColor(0x10FFFFFF);
            cv.drawArc(new RectF(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f), -60, 50, false, p);
            // Label central con la carátula
            float lr = r * 0.34f;
            if (coverActual != null && !coverActual.isRecycled()) {
                Path path = new Path(); path.addCircle(cx, cy, lr, Path.Direction.CW);
                cv.save(); cv.clipPath(path);
                Rect src = new Rect(0, 0, coverActual.getWidth(), coverActual.getHeight());
                RectF dst = new RectF(cx - lr, cy - lr, cx + lr, cy + lr);
                p.setStyle(Paint.Style.FILL);
                cv.drawBitmap(coverActual, src, dst, p);
                cv.restore();
            } else {
                p.setStyle(Paint.Style.FILL); p.setColor(0xFFC0392B);
                cv.drawCircle(cx, cy, lr, p);
                p.setColor(0xFFFFFFFF); p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(lr * 0.5f); p.setFakeBoldText(true);
                cv.drawText("JFV", cx, cy + lr * 0.18f, p);
            }
            // Borde del label
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, size * 0.006f)); p.setColor(0x66000000);
            cv.drawCircle(cx, cy, lr, p);
            // Hueco central
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF141118);
            cv.drawCircle(cx, cy, size * 0.022f, p);
            discoBmp = bmp;
        } catch (Throwable e) { discoBmp = null; }
    }

    protected void onDraw(Canvas canvas) {
        if (!activo) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int disco = (int) (Math.min(w, h) * 0.86f);   // tamaño del disco
        if (disco < 20) return;
        if (discoBmp == null || lastSize != disco) { lastSize = disco; construirDisco(disco); }
        float cx = h * 0.52f;
        float cy = h * 0.5f;
        if (discoBmp != null) {
            canvas.save();
            canvas.rotate(angulo, cx, cy);
            canvas.drawBitmap(discoBmp, cx - disco / 2f, cy - disco / 2f, p);
            canvas.restore();
        } else {
            // respaldo: si no se pudo armar el bitmap, dibujar un disco simple para que SIEMPRE se vea algo
            float r = disco / 2f;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF0B0B0D);
            canvas.drawCircle(cx, cy, r, p);
            p.setColor(0xFFC0392B); canvas.drawCircle(cx, cy, r * 0.34f, p);
            p.setColor(0xFF141118); canvas.drawCircle(cx, cy, disco * 0.03f, p);
        }
        if (activo) {
            if (sonando) { angulo += 1.1f; if (angulo >= 360f) angulo -= 360f; }
            postInvalidateDelayed(33);      // redibujo continuo mientras esté activo (aparece aunque esté en pausa)
        }
    }
}
