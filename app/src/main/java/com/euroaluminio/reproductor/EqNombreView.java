package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Nombre del artista (blanco) + cancion (color del tema) con barras de ecualizador.
 * REESCRITO DESDE CERO con la TECNICA EXACTA del PC:
 *  - El PC pide los datos frescos EL MISMO en cada cuadro (60fps) con getByteFrequencyData.
 *    Aqui hacemos lo mismo: visualizer.getFft() directo dentro del dibujo, cada cuadro.
 *  - Suavizado temporal 0.62 en magnitudes LINEALES (lo que hace Web Audio por dentro).
 *  - Equivalente de getByteFrequencyData: dB mapeados a 0..1.
 *  - Barras: usable=90%, promedio por banda, reparto (0.5 + 1.0*i/N), tope 1.
 *  - Suavizado de barra: sube AL INSTANTE, baja 0.70/0.30 POR CUADRO (exacto PC).
 *  - Dibujo: linea base 2px, altura v*H*0.7, barra fina 62% del paso (exacto PC).
 */
public class EqNombreView extends View {
    private String artista = "", cancion = "";
    private int color = 0xFFFFC107;
    private boolean activo = false, sonando = false;
    private static final int N = 40;                       // 40 barras (como PC)
    private final float[] magLin = new float[80];          // magnitudes lineales suavizadas (0.62 como Web Audio)
    private final float[] eqSmooth = new float[N];         // sube instante / baja 0.70-0.30 (exacto PC)
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastT = 0;
    private final java.util.Random rnd = new java.util.Random();
    private boolean tieneAudio = false;
    private android.media.audiofx.Visualizer viz = null;   // para pedir el FFT DIRECTO cada cuadro (tecnica PC)
    private byte[] buf = null;
    private final float[] vTmp = new float[N];             // valor crudo de cada barra en este cuadro

    public EqNombreView(Context c) { super(c); init(); }
    public EqNombreView(Context c, AttributeSet a) { super(c, a); init(); }
    private void init() {
        pText.setTypeface(Typeface.DEFAULT_BOLD);
        pBar.setStyle(Paint.Style.FILL);
    }

    /** El reproductor nos presta el Visualizer para pedir el FFT directo cada cuadro (como hace el PC). */
    public void setVisualizer(android.media.audiofx.Visualizer v) { viz = v; }

    // ===== FFT PROPIO EN FLOTANTES (la tecnica real del navegador) =====
    // El FFT que entrega el radio es de 8 bits: una barra de agudos solo puede tomar 3-4 valores
    // (escalones). El navegador calcula su FFT en flotantes desde la onda cruda -> continuo y fluido.
    // Aqui hacemos LO MISMO: onda cruda (getWaveForm) + ventana Blackman + FFT flotante de 128 puntos.
    private static final int NF = 128;
    private static final float[] WIN = new float[NF];
    private static final int[] REV = new int[NF];
    static {
        for (int i = 0; i < NF; i++) {
            WIN[i] = (float) (0.42 - 0.5 * Math.cos(2 * Math.PI * i / (NF - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (NF - 1)));
            int r = 0, x = i;
            for (int bb = 0; bb < 7; bb++) { r = (r << 1) | (x & 1); x >>= 1; }
            REV[i] = r;
        }
    }
    private final float[] fre = new float[NF];
    private final float[] fim = new float[NF];

    private boolean procesarOnda(byte[] wave) {
        if (wave == null || wave.length < NF) return false;
        int mn = 255, mx = 0;
        for (int i = 0; i < NF; i++) { int s = wave[i] & 0xFF; if (s < mn) mn = s; if (s > mx) mx = s; }
        if (mx - mn < 3) return false;   // onda plana: este radio no da onda util, usar respaldo
        for (int i = 0; i < NF; i++) {
            int s = (wave[i] & 0xFF) - 128;
            fre[REV[i]] = s * WIN[i];
            fim[REV[i]] = 0f;
        }
        for (int len = 2; len <= NF; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr0 = (float) Math.cos(ang), wi0 = (float) Math.sin(ang);
            for (int i = 0; i < NF; i += len) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < len / 2; j++) {
                    int aa = i + j, b2 = i + j + len / 2;
                    float xr = fre[b2] * wr - fim[b2] * wi;
                    float xi = fre[b2] * wi + fim[b2] * wr;
                    fre[b2] = fre[aa] - xr; fim[b2] = fim[aa] - xi;
                    fre[aa] += xr; fim[aa] += xi;
                    float nwr = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = nwr;
                }
            }
        }
        int nb = NF / 2;   // 64 bandas, igual que el PC (fftSize 128)
        // suavizado temporal 0.62 en magnitudes LINEALES (lo que Web Audio hace por dentro)
        for (int k = 1; k < nb && k < magLin.length; k++) {
            float m = (float) Math.sqrt(fre[k] * fre[k] + fim[k] * fim[k]);
            magLin[k] = magLin[k] * 0.62f + m * 0.38f;
        }
        // barras EXACTO como pintarBarras del PC, con dB flotantes (ventana tipo [-100,-30] del navegador)
        float ref = 3400f;                 // magnitud de un tono a todo volumen (referencia 0 dBFS)
        int usable = (int) Math.floor(nb * 0.9f);
        for (int i = 0; i < N; i++) {
            int lo = (i * usable) / N + 1;
            int hi = ((i + 1) * usable) / N + 1;
            if (hi <= lo) hi = lo + 1;
            float sum = 0f; int c = 0;
            for (int b = lo; b < hi && b < nb; b++) {
                float dbfs = (float) (20.0 * Math.log10((magLin[b] + 0.001f) / ref));   // <= 0
                float bv = (dbfs + 70f) / 58f;     // ventana [-70,-12] dBFS -> 0..1
                if (bv < 0f) bv = 0f;
                if (bv > 1f) bv = 1f;
                sum += bv; c++;
            }
            float v = (c > 0) ? sum / c : 0f;
            v *= (0.5f + 1.0f * ((float) i / N));   // reparto exacto del PC
            if (v > 1f) v = 1f;
            vTmp[i] = v;
        }
        return true;
    }

    /** Respaldo: si no hay Visualizer directo, aceptar datos empujados por el listener. */
    public void setFft(byte[] fft) {
        if (viz != null) return;                 // con acceso directo, este camino no hace falta
        if (fft == null || fft.length < 8) return;
        procesar(fft);
        tieneAudio = true;
        if (activo) postInvalidate();
    }

    // ===== TECNICA EXACTA DEL PC =====
    private void procesar(byte[] fft) {
        int bins = fft.length / 2;
        int nb = Math.min(bins, magLin.length);
        // 1) magnitudes LINEALES + suavizado temporal 0.62 (lo que Web Audio hace por dentro)
        for (int k = 1; k < nb; k++) {
            int p = k * 2;
            if (p + 1 >= fft.length) break;
            float re = fft[p], im = fft[p + 1];
            float m = (float) Math.sqrt(re * re + im * im);
            magLin[k] = magLin[k] * 0.62f + m * 0.38f;
        }
        // 2) barras EXACTO como pintarBarras del PC
        int usable = (int) Math.floor(nb * 0.9f);
        for (int i = 0; i < N; i++) {
            int lo = (i * usable) / N + 1;
            int hi = ((i + 1) * usable) / N + 1;
            if (hi <= lo) hi = lo + 1;
            float sum = 0f; int c = 0;
            for (int b = lo; b < hi && b < nb; b++) {
                // equivalente de getByteFrequencyData: dB del bin mapeado a 0..1
                float db = (float) (20.0 * Math.log10(magLin[b] + 1.0));   // 0..~45 con FFT de 8 bits
                float bv = db / 45f;
                if (bv > 1f) bv = 1f;
                if (bv < 0f) bv = 0f;
                sum += bv; c++;
            }
            float v = (c > 0) ? sum / c : 0f;
            v *= (0.5f + 1.0f * ((float) i / N));    // menos graves dominantes, mas parejo (exacto PC)
            if (v > 1f) v = 1f;
            vTmp[i] = v;
        }
    }

    public void setInfo(String art, String can) {
        String a = (art == null) ? "" : art.trim();
        String c = (can == null) ? "" : can.trim();
        if (a.equalsIgnoreCase("Desconocido") || a.equalsIgnoreCase("<unknown>") || a.equalsIgnoreCase("unknown")) a = "";
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

        // Ajustar el texto para que QUEPA (achica el tamano; si aun asi es muy largo, corta con ...)
        String uA = upper(artista), uC = upper(cancion);
        float maxWa = w - (x + 26 * sc) - 8 * sc;
        float maxWc = w - x - 8 * sc;
        float szA = ajustarSize(uA, 22 * sc, 14 * sc, maxWa); uA = recortar(uA, szA, maxWa);
        float szC = ajustarSize(uC, 31 * sc, 17 * sc, maxWc); uC = recortar(uC, szC, maxWc);

        // Texto como el PC: letra GRUESA + SOMBRA suave
        pText.setFakeBoldText(true);
        pText.setShadowLayer(6f * sc, 0, 3f * sc, 0xE6000000);
        pText.setColor(0xFFFFFFFF);
        pText.setTextSize(szA);
        cv.drawText(uA, x + (int) (26 * sc), y, pText);
        pText.setColor(color);
        pText.setTextSize(szC);
        cv.drawText(uC, x, y + (int) (30 * sc), pText);
        pText.clearShadowLayer();

        // === PEDIR DATOS FRESCOS ESTE CUADRO (la tecnica del PC: 60 veces por segundo) ===
        if (sonando && viz != null) {
            boolean ok = false;
            try {
                int cs = viz.getCaptureSize();
                if (buf == null || buf.length != cs) buf = new byte[cs];
                viz.getWaveForm(buf);
                ok = procesarOnda(buf);          // FFT PROPIO en flotantes (continuo y fluido, como el navegador)
            } catch (Throwable t) {}
            if (!ok) {
                try {
                    int cs = viz.getCaptureSize();
                    if (buf == null || buf.length != cs) buf = new byte[cs];
                    viz.getFft(buf);
                    procesar(buf);               // respaldo: FFT de 8 bits del radio
                    ok = true;
                } catch (Throwable t) {}
            }
            if (ok) tieneAudio = true;
        }
        long now = System.currentTimeMillis();
        if (!tieneAudio && now - lastT > 60) {    // respaldo: si el radio no da FFT, animacion por tiempo
            lastT = now;
            for (int i = 0; i < N; i++) vTmp[i] = sonando ? (0.10f + rnd.nextFloat() * 0.75f) : 0.01f;
        }

        // === BARRAS: exacto pintarBarras del PC ===
        float by = y + (int) (46 * sc);
        float bx = x + 2;
        float bwidth = 190 * sc;
        pBar.setColor(color);
        cv.drawRect(bx, by, bx + bwidth, by + 2 * sc, pBar);   // linea base (como PC)
        float step = bwidth / N;
        float H = 60 * sc;                                     // alto de la tira
        for (int i = 0; i < N; i++) {
            float v = sonando ? vTmp[i] : 0.01f;               // pausado -> barras abajo (como PC)
            // suavizado del PC: sube AL INSTANTE, baja 0.70/0.30 por cuadro
            if (v > eqSmooth[i]) eqSmooth[i] = v;
            else eqSmooth[i] = eqSmooth[i] * 0.70f + v * 0.30f;
            float bh = Math.max(2f, eqSmooth[i] * H * 0.7f);   // altura moderada (exacto PC)
            float barW = Math.max(1.5f, step * 0.62f);         // barras finas (exacto PC)
            cv.drawRect(bx + i * step, by + 2 * sc, bx + i * step + barW, by + 2 * sc + bh, pBar);
        }
        if (activo && sonando) postInvalidateDelayed(16);      // ~60 cuadros/seg (como requestAnimationFrame)
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
