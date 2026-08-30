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
    private final float[] magSmooth = new float[N];   // suavizado interno como el navegador (smoothingTimeConstant 0.62)
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastT = 0;
    private final java.util.Random rnd = new java.util.Random();
    private boolean tieneAudio = false;   // true cuando llega FFT real
    private float picoGlobal = 8f;         // para auto-ganancia (se adapta al volumen de la canción)

    // ===== ECUALIZADOR NUEVO (desde cero): mapeo LOGARITMICO + PICO por banda =====
    // Clave para que se vea NATURAL como el PC: no promediar (eso aplana). Se toma el PICO de cada
    // banda y se reparten las frecuencias en escala logaritmica (como el oido), asi cada barra
    // muestra su frecuencia con relieve -> barras picudas y desparejas que bailan con el ritmo.
    public void setFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        tieneAudio = true;
        int bins = fft.length / 2;
        float fMin = 2f, fMax = bins - 1f;
        float maxNivel = 0f;
        for (int i = 0; i < N; i++) {
            // rango de frecuencias de ESTA barra (logaritmico: las graves ocupan pocas, las agudas muchas)
            int lo = (int) (fMin * Math.pow(fMax / fMin, (float) i / N));
            int hi = (int) (fMin * Math.pow(fMax / fMin, (float) (i + 1) / N));
            if (lo < 1) lo = 1;
            if (hi <= lo) hi = lo + 1;
            if (hi > bins) hi = bins;
            // PICO (maximo) de la banda -> conserva el golpe, no lo lava como el promedio
            float mx = 0f;
            for (int b = lo; b < hi; b++) {
                int p = b * 2;
                if (p + 1 >= fft.length) break;
                float re = fft[p], im = fft[p + 1];
                float m = (float) Math.sqrt(re * re + im * im);
                if (m > mx) mx = m;
            }
            // a decibeles (perceptual) + leve empuje a los agudos para que participen
            float db = (float) (20.0 * Math.log10(mx + 1.0));
            db += ((float) i / N) * 7f;
            // suavizado ligero en el tiempo (estable pero responde al golpe)
            magSmooth[i] = magSmooth[i] * 0.35f + db * 0.65f;
            if (magSmooth[i] > maxNivel) maxNivel = magSmooth[i];
        }
        // techo adaptado a la cancion, pero con PISO ALTO y bajada MUY lenta:
        // en intros suaves las barras se ven PEQUENAS (natural), no amplificadas como un "viaje"
        picoGlobal = Math.max(maxNivel, picoGlobal * 0.998f);
        if (picoGlobal < 30f) picoGlobal = 30f;
        float piso = picoGlobal - 26f;                 // ventana de 26 dB (relieve: fuertes altas, suaves bajas)
        float rango = picoGlobal - piso;
        for (int i = 0; i < N; i++) {
            float v = (magSmooth[i] - piso) / rango;   // relieve natural (picudo y musical)
            // compuerta ABSOLUTA: banda casi muda = barra casi cero (no baila con el ruidito)
            if (magSmooth[i] < 12f) v *= (magSmooth[i] / 12f);
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            target[i] = v;
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
        String a = (art == null) ? "" : art.trim();
        String c = (can == null) ? "" : can.trim();
        if (a.equalsIgnoreCase("Desconocido")) a = "";
        // regla del PC: si no hay artista y el nombre tiene " - ", se divide (primera parte arriba, resto abajo)
        if (a.length() == 0 && c.indexOf(" - ") >= 0) {
            int q = c.indexOf(" - ");
            a = c.substring(0, q).trim();
            c = c.substring(q + 3).trim();
        }
        artista = a;
        cancion = c;
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
            if (target[i] > h[i]) h[i] = target[i];                       // sube al instante
            else h[i] = h[i] * 0.62f + target[i] * 0.38f;                 // baja con caida visible (picudo, natural)
            float bh = h[i] * 42 * sc + 1.5f;                            // tira compacta (como PC)
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
