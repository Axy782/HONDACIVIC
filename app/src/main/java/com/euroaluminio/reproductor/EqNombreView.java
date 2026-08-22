package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Efecto tipo "video musical": nombre del artista (blanco) + canción (amarillo)
 * con barras de ecualizador que caen HACIA ABAJO, animadas al ritmo. Ubicado a la
 * DERECHA, al medio. Ligero (animación por tiempo) para no cargar el radio viejo.
 */
public class EqNombreView extends View {
    private String artista = "", cancion = "";
    private int color = 0xFFFFC107;      // amarillo como el video
    private boolean activo = false, sonando = false;
    private static final int N = 22;
    private final float[] h = new float[N];
    private final float[] target = new float[N];
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastT = 0;
    private final java.util.Random rnd = new java.util.Random();
    private boolean tieneAudio = false;   // true cuando llega FFT real

    // Recibe el análisis de frecuencias del audio (graves, medios, agudos)
    public void setFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        tieneAudio = true;
        int bins = fft.length / 2;
        for (int i = 0; i < N; i++) {
            int idx = 2 + (i * (bins - 2) / N) * 2;   // de graves (izq) a agudos (der)
            if (idx + 1 >= fft.length) { target[i] = 0.05f; continue; }
            float re = fft[idx], im = fft[idx + 1];
            float mag = (float) Math.sqrt(re * re + im * im);
            float boost = 1.0f + (1.0f - (float) i / N) * 0.8f;   // los graves pesan más (como el video)
            float val = (mag / 90f) * boost;
            if (val > 1f) val = 1f;
            if (val < 0.04f) val = 0.04f;
            if (val > target[i]) target[i] = val; else target[i] = target[i] * 0.75f + val * 0.25f;   // sube rápido, baja suave
        }
        if (activo) postInvalidate();
    }

    public EqNombreView(Context c) { super(c); init(); }
    public EqNombreView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() {
        pText.setTypeface(Typeface.DEFAULT_BOLD);
        pBar.setStyle(Paint.Style.FILL);
    }

    public void setInfo(String art, String can) {
        artista = (art == null) ? "" : art;
        cancion = (can == null) ? "" : can;
        if (activo) invalidate();
    }
    public void setColor(int col) { color = col; }
    public void setActivo(boolean a) {
        activo = a;
        setVisibility(a ? VISIBLE : GONE);
        if (a) invalidate();
    }
    public void setSonando(boolean s) {
        boolean antes = sonando;
        sonando = s;
        if (activo && s && !antes) invalidate();
    }

    protected void onDraw(Canvas cv) {
        if (!activo) return;
        int w = getWidth(), hh = getHeight();
        float sc = Math.max(1f, w / 760f);
        int bw = (int) (200 * sc);
        int x = w - bw - (int) (24 * sc);
        int y = hh / 2 - (int) (30 * sc);

        // sombra sutil para que se lea sobre cualquier carátula
        pText.setColor(0xCC000000);
        pText.setTextSize(22 * sc);
        cv.drawText(upper(artista), x + (int) (28 * sc), y + 2, pText);
        pText.setTextSize(30 * sc);
        cv.drawText(upper(cancion), x + 2, y + (int) (30 * sc) + 2, pText);

        // artista (blanco)
        pText.setColor(0xFFFFFFFF);
        pText.setTextSize(22 * sc);
        cv.drawText(upper(artista), x + (int) (26 * sc), y, pText);
        // canción (color)
        pText.setColor(color);
        pText.setTextSize(30 * sc);
        cv.drawText(upper(cancion), x, y + (int) (30 * sc), pText);

        // línea + barras hacia ABAJO
        float by = y + (int) (46 * sc);
        float bx = x + 2;
        float bwidth = 190 * sc;
        pBar.setColor(color);
        cv.drawRect(bx, by, bx + bwidth, by + 2 * sc, pBar);
        float step = bwidth / N;

        long now = System.currentTimeMillis();
        if (!tieneAudio && now - lastT > 60) {   // respaldo: si el radio no da FFT, animación por tiempo
            lastT = now;
            for (int i = 0; i < N; i++) target[i] = sonando ? (0.15f + rnd.nextFloat() * 0.85f) : 0.05f;
        }
        if (!sonando) { for (int i = 0; i < N; i++) target[i] = 0.05f; }
        for (int i = 0; i < N; i++) {
            h[i] += (target[i] - h[i]) * 0.4f;
            float bh = h[i] * 26 * sc + 2;
            cv.drawRect(bx + i * step, by, bx + i * step + step * 0.5f, by + bh, pBar);
        }
        if (activo && sonando && !tieneAudio) postInvalidateDelayed(70);   // con FFT, se redibuja cuando llega audio
    }

    private String upper(String s) { try { return s.toUpperCase(); } catch (Exception e) { return s; } }
}
