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
    private static final int N = 40;
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
        int usable = (int) (bins * 0.9f);
        for (int i = 0; i < N; i++) {
            // reparto PAREJO por bandas (promedio real, igual que en PC)
            int lo = i * usable / N + 1;
            int hi = (i + 1) * usable / N + 1;
            if (hi <= lo) hi = lo + 1;
            if (hi > bins) hi = bins;
            float sum = 0; int cnt = 0;
            for (int b = lo; b < hi; b++) {
                int p = b * 2;
                if (p + 1 >= fft.length) break;
                float re = fft[p], im = fft[p + 1];
                sum += (float) Math.sqrt(re * re + im * im); cnt++;
            }
            float mag = (cnt > 0) ? sum / cnt : 0;
            // escala dB con VENTANA (igual que getByteFrequencyData del PC): las bandas suaves caen y las fuertes saltan -> baila natural
            float db = (float) (20.0 * Math.log10(mag + 1.0));   // ~0..45
            float v = (db - 6f) / (40f - 6f);                    // ventana: <6dB baja a 0, >40dB llena
            v *= (0.7f + 0.7f * ((float) i / N));                // sube un poco los agudos
            if (v > 1f) v = 1f;
            if (v < 0f) v = 0f;
            target[i] = v;                               // una sola suavizacion: la hace onDraw (sube rapido, baja agil)
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

        // Ajustar el texto para que QUEPA (achica el tamaño; si aun asi es muy largo, corta con ...)
        String uA = upper(artista), uC = upper(cancion);
        float maxWa = w - (x + 26 * sc) - 8 * sc;
        float maxWc = w - x - 8 * sc;
        float szA = ajustarSize(uA, 22 * sc, 14 * sc, maxWa); uA = recortar(uA, szA, maxWa);
        float szC = ajustarSize(uC, 30 * sc, 17 * sc, maxWc); uC = recortar(uC, szC, maxWc);

        // sombra sutil para que se lea sobre cualquier carátula
        pText.setColor(0xCC000000);
        pText.setTextSize(szA);
        cv.drawText(uA, x + (int) (28 * sc), y + 2, pText);
        pText.setTextSize(szC);
        cv.drawText(uC, x + 2, y + (int) (30 * sc) + 2, pText);

        // artista (blanco)
        pText.setColor(0xFFFFFFFF);
        pText.setTextSize(szA);
        cv.drawText(uA, x + (int) (26 * sc), y, pText);
        // canción (color)
        pText.setColor(color);
        pText.setTextSize(szC);
        cv.drawText(uC, x, y + (int) (30 * sc), pText);

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
            if (target[i] > h[i]) h[i] += (target[i] - h[i]) * 0.85f;     // sube ágil
            else h[i] += (target[i] - h[i]) * 0.32f;                      // baja con cuerpo (natural, no seco)
            float bh = h[i] * 60 * sc + 1.5f;                            // barras más altas
            cv.drawRect(bx + i * step, by, bx + i * step + step * 0.6f, by + bh, pBar);
        }
        // redibujo fluido (~30 cuadros) mientras suena, aunque el FFT llegue lento
        if (activo && sonando) postInvalidateDelayed(33);
    }

    private float ajustarSize(String t, float base, float min, float maxW) {
        if (t == null || t.length() == 0) return base;
        float sz = base; pText.setTextSize(sz);
        while (sz > min && pText.measureText(t) > maxW) { sz -= 1f; pText.setTextSize(sz); }
        return sz;
    }
    private String recortar(String t, float sz, float maxW) {
        if (t == null) return "";
        pText.setTextSize(sz);
        if (pText.measureText(t) <= maxW) return t;
        while (t.length() > 1 && pText.measureText(t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }
    private String upper(String s) { try { return s.toUpperCase(); } catch (Exception e) { return s; } }
}
