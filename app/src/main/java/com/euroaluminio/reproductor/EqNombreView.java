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
    private static final int N = 30;
    private final float[] h = new float[N];
    private final float[] target = new float[N];
    private final float[] pico = new float[N];   // pico por banda: cada instrumento con su propia sensibilidad
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastT = 0;
    private final java.util.Random rnd = new java.util.Random();
    private boolean tieneAudio = false;   // true cuando llega FFT real
    private float picoGlobal = 8f;         // para auto-ganancia (se adapta al volumen de la canción)

    // Recibe el análisis de frecuencias del audio y hace que las barras BAILEN con el ritmo
    public void setFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        tieneAudio = true;
        int bins = fft.length / 2;
        for (int i = 0; i < N; i++) {
            // Reparto logaritmico parejo (cada barra una banda distinta: graves -> agudos)
            float fMin = 1f, fMax = bins - 1f;
            int start = (int) (fMin * Math.pow(fMax / fMin, (float) i / N));
            int end = (int) (fMin * Math.pow(fMax / fMin, (float) (i + 1) / N));
            if (start < 1) start = 1;
            if (end <= start) end = start + 1;
            if (end > bins) end = bins;
            float sum = 0; int cnt = 0;
            for (int b = start; b < end; b++) {
                int p = b * 2;
                if (p + 1 >= fft.length) break;
                float re = fft[p], im = fft[p + 1];
                sum += (float) Math.sqrt(re * re + im * im); cnt++;
            }
            float mag = (cnt > 0) ? sum / cnt : 0;
            // COMPUERTA: si esta banda casi no tiene energia, la barra baja (queda plana)
            if (mag < 3.5f) { target[i] *= 0.55f; continue; }
            // PICO POR BANDA: cada banda usa su propia altura -> cuando ESE instrumento suena, SU barra salta
            pico[i] = Math.max(mag, pico[i] * 0.96f);
            if (pico[i] < 9f) pico[i] = 9f;
            float val = mag / pico[i];               // 0..1 relativo a esta banda
            val = 0.12f + val * 0.88f;               // cuerpo: cuando suena, se ve con fuerza
            if (val > 1f) val = 1f;
            if (val > target[i]) target[i] = val;    // ataque: salta al instante
            else target[i] = target[i] * 0.62f + val * 0.38f;   // caida agil
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
        if (!sonando) { for (int i = 0; i < N; i++) target[i] = 0.01f; }
        for (int i = 0; i < N; i++) {
            if (target[i] > h[i]) h[i] += (target[i] - h[i]) * 0.8f;     // sube rápido
            else h[i] += (target[i] - h[i]) * 0.28f;                     // baja suave (natural)
            float bh = h[i] * 34 * sc + 1.5f;
            cv.drawRect(bx + i * step, by, bx + i * step + step * 0.55f, by + bh, pBar);
        }
        if (activo && sonando && !tieneAudio) postInvalidateDelayed(70);   // con FFT, se redibuja cuando llega audio
    }

    private String upper(String s) { try { return s.toUpperCase(); } catch (Exception e) { return s; } }
}
