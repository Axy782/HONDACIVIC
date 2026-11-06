package com.euroaluminio.reproductor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import java.nio.ByteBuffer;

// Analizador de audio en 16 bits (como el PC).
// Decodifica la cancion por su cuenta (sin tocar la que suena), la guarda unos segundos adelante
// y calcula el espectro sincronizado con la posicion del reproductor, ~50 veces por segundo.
// Si algo falla (formato raro, radio sin fuerza) avisa y la app vuelve al Visualizer de siempre.
public class AnalizadorAudio {

    public interface Oyente { void niveles(float[] bandas); void fallo(); }
    public volatile String motivo = "";
    public volatile long cuadros = 0;     // analisis con audio real (si sube, el eq de 16 bits esta trabajando)   // por que fallo (para mostrarlo en pantalla)

    private static final int N = 24;          // barras (menos y mas anchas: se lee mejor)
    private static final int FFT = 2048;      // tamano del analisis
    private static final int RING_SEG = 8;    // segundos guardados
    private static final float ADELANTO_SEG = 2.5f;

    private final String path;
    private final MediaPlayer mp;
    private final Oyente oyente;
    private volatile boolean corriendo = false;
    private Thread hiloDecod, hiloFft;

    private int rate = 44100, canales = 2;
    private short[] ring;                     // mono
    private volatile long ringInicio = 0;     // posicion (en muestras) de ring[0]
    private volatile long ringFin = 0;        // ultima muestra decodificada (exclusiva)
    private final Object lock = new Object();
    private volatile long pedirSalto = -1;    // posicion a la que hay que saltar (ms), -1 = no

    // sincronizacion con el reproductor
    private long ultPosMs = -1, ultPosT = 0;

    private final float[] ventana = new float[FFT];
    private final float[] re = new float[FFT], im = new float[FFT];
    private final float[] magSuav = new float[FFT / 2];
    private final int[] bandaLo = new int[N], bandaHi = new int[N];
    private final float[] salida = new float[N];
    private final float[] techo = new float[N];   // nivel maximo reciente de cada banda (auto-calibracion por instrumento)
    private final float[] piso = new float[N];    // nivel minimo reciente
    private final float[] dbAnt = new float[N];   // nivel anterior de cada banda (para detectar golpes)
    private final float[] golpe = new float[N];   // empujon que da cada golpe (bombo, tambora, guira) y que se apaga solo
    private float graveProm = 0f;                 // energia promedio de los graves (para el "pum" del bombo)

    public AnalizadorAudio(String path, MediaPlayer mp, Oyente o) { this.path = path; this.mp = mp; this.oyente = o; }

    public boolean iniciar() {
        try {
            MediaExtractor ex = new MediaExtractor();
            ex.setDataSource(path);
            int pista = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { pista = i; fmt = f; break; }
            }
            if (pista < 0) { ex.release(); motivo = "el archivo no tiene pista de audio"; return false; }
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime.contains("wma") || mime.contains("opus")) { ex.release(); motivo = "formato " + mime + " no soportado"; return false; }
            try { rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); } catch (Exception e) {}
            try { canales = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); } catch (Exception e) {}
            if (rate < 8000 || rate > 96000) rate = 44100;
            if (canales < 1 || canales > 8) canales = 2;
            ex.selectTrack(pista);
            final MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            ring = new short[rate * RING_SEG];
            prepararBandas();
            for (int i = 0; i < FFT; i++) {   // ventana Blackman (igual que el navegador)
                double a = 2 * Math.PI * i / (FFT - 1);
                ventana[i] = (float) (0.42 - 0.5 * Math.cos(a) + 0.08 * Math.cos(2 * a));
            }
            corriendo = true;
            final MediaExtractor fex = ex;
            hiloDecod = new Thread(new Runnable() { public void run() { decodificar(fex, codec); } }, "eq-decod");
            hiloDecod.setPriority(Thread.MIN_PRIORITY + 1);
            hiloDecod.start();
            hiloFft = new Thread(new Runnable() { public void run() { analizar(); } }, "eq-fft");
            hiloFft.setPriority(Thread.MIN_PRIORITY + 1);
            hiloFft.start();
            return true;
        } catch (Throwable t) { corriendo = false; motivo = "no se pudo abrir el decodificador: " + t; return false; }
    }

    public void detener() {
        corriendo = false;
        try { if (hiloFft != null) hiloFft.interrupt(); } catch (Exception e) {}
        try { if (hiloDecod != null) hiloDecod.interrupt(); } catch (Exception e) {}
    }

    public void saltoA(int ms) { pedirSalto = ms; }

    // ---- posicion actual estimada del reproductor (getCurrentPosition es lento en radios viejos) ----
    private long posMuestras() {
        long now = System.currentTimeMillis();
        long p = -1; boolean sonando = false;
        try { p = mp.getCurrentPosition(); sonando = mp.isPlaying(); } catch (Throwable e) {}
        if (p < 0) p = ultPosMs;
        if (p != ultPosMs) { ultPosMs = p; ultPosT = now; }
        long est = sonando ? ultPosMs + (now - ultPosT) : ultPosMs;
        if (est < 0) est = 0;
        return est * rate / 1000L;
    }

    private void decodificar(MediaExtractor ex, MediaCodec codec) {
        ByteBuffer[] inBufs = codec.getInputBuffers();
        ByteBuffer[] outBufs = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean finEntrada = false;
        long decodFin = 0;              // muestras decodificadas hasta aqui
        int errores = 0;
        try {
            while (corriendo) {
                // ¿hay que saltar? (el usuario adelanto la cancion o estamos muy lejos)
                long salto = pedirSalto;
                long pos = posMuestras();
                boolean lejos = (pos < ringInicio) || (pos > ringFin + (long) rate * 2);
                if (salto >= 0 || lejos) {
                    long aMs = (salto >= 0) ? salto : (pos * 1000L / rate);
                    pedirSalto = -1;
                    try { ex.seekTo(Math.max(0, aMs - 300) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); codec.flush(); } catch (Throwable e) {}
                    long t = 0; try { t = ex.getSampleTime(); } catch (Throwable e) {}
                    decodFin = Math.max(0, t) * rate / 1000000L;
                    synchronized (lock) { ringInicio = decodFin; ringFin = decodFin; }
                    finEntrada = false;
                }
                // si ya vamos suficientemente adelante, descansar
                if (ringFin - pos > (long) (rate * ADELANTO_SEG) || finEntrada) { Thread.sleep(25); continue; }

                int ib = codec.dequeueInputBuffer(5000);
                if (ib >= 0) {
                    ByteBuffer b = inBufs[ib]; b.clear();
                    int n = ex.readSampleData(b, 0);
                    if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finEntrada = true; }
                    else { codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                }
                int ob = codec.dequeueOutputBuffer(info, 5000);
                if (ob == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { outBufs = codec.getOutputBuffers(); }
                else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    try {
                        MediaFormat f = codec.getOutputFormat();
                        int r2 = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), c2 = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (r2 > 0 && r2 != rate) { rate = r2; synchronized (lock) { ring = new short[rate * RING_SEG]; ringInicio = ringFin = decodFin = posMuestras(); } prepararBandas(); }
                        if (c2 > 0) canales = c2;
                    } catch (Throwable e) {}
                } else if (ob >= 0) {
                    ByteBuffer ob2 = outBufs[ob];
                    if (info.size > 0) {
                        ob2.position(info.offset); ob2.limit(info.offset + info.size);
                        ob2.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        int frames = info.size / (2 * canales);
                        // alinear con el tiempo real del decodificador (por si el salto cayo antes)
                        long tMuestra = info.presentationTimeUs * rate / 1000000L;
                        if (Math.abs(tMuestra - decodFin) > rate / 4) { decodFin = tMuestra; synchronized (lock) { ringInicio = decodFin; ringFin = decodFin; } }
                        synchronized (lock) {
                            int len = ring.length;
                            for (int f = 0; f < frames; f++) {
                                int acc = 0;
                                for (int c = 0; c < canales; c++) acc += ob2.getShort();
                                ring[(int) ((decodFin + f) % len)] = (short) (acc / canales);
                            }
                            decodFin += frames;
                            ringFin = decodFin;
                            if (ringFin - ringInicio > len) ringInicio = ringFin - len;
                        }
                    }
                    codec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) finEntrada = true;
                }
            }
        } catch (InterruptedException ie) {
        } catch (Throwable t) {
            errores++;
            motivo = "error al decodificar: " + t;
            if (corriendo) { corriendo = false; try { oyente.fallo(); } catch (Throwable e) {} }
        } finally {
            try { codec.stop(); } catch (Throwable e) {}
            try { codec.release(); } catch (Throwable e) {}
            try { ex.release(); } catch (Throwable e) {}
        }
    }

    private void prepararBandas() {
        // bandas en escala logaritmica: graves con varias barras (bajo, bombo), medios (voz, piano), agudos (platillos)
        double fMin = 50, fMax = Math.min(14000, rate / 2.0 * 0.95);
        for (int i = 0; i < N; i++) {
            double f0 = fMin * Math.pow(fMax / fMin, (double) i / N);
            double f1 = fMin * Math.pow(fMax / fMin, (double) (i + 1) / N);
            int lo = (int) Math.floor(f0 * FFT / rate), hi = (int) Math.ceil(f1 * FFT / rate);
            if (lo < 1) lo = 1; if (hi <= lo) hi = lo + 1; if (hi > FFT / 2) hi = FFT / 2;
            bandaLo[i] = lo; bandaHi[i] = hi;
        }
    }

    private void analizar() {
        final float[] tmp = new float[FFT];
        long siguiente = System.currentTimeMillis();
        try {
            while (corriendo) {
                siguiente += 20;   // ~50 analisis por segundo
                long pos = posMuestras();
                boolean sonando = false; try { sonando = mp.isPlaying(); } catch (Throwable e) {}
                boolean hay;
                synchronized (lock) {
                    hay = sonando && pos - FFT >= ringInicio && pos <= ringFin;
                    if (hay) { int len = ring.length; long ini = pos - FFT; for (int i = 0; i < FFT; i++) tmp[i] = ring[(int) ((ini + i) % len)]; }
                }
                if (hay) {
                    for (int i = 0; i < FFT; i++) { re[i] = tmp[i] * ventana[i] / 32768f; im[i] = 0f; }
                    fft(re, im);
                    for (int k = 0; k < FFT / 2; k++) {
                        float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / (FFT / 4f);
                        magSuav[k] = magSuav[k] * 0.35f + m * 0.65f;   // menos suavizado: las barras siguen cada golpe
                    }
                    // golpe de los graves (bombo / tambora): energia de las 5 primeras bandas contra su promedio
                    float grave = 0f;
                    for (int i = 0; i < 5; i++) { float s2 = 0f; for (int k = bandaLo[i]; k < bandaHi[i]; k++) s2 += magSuav[k]; grave += s2; }
                    boolean pum = graveProm > 0f && grave > graveProm * 1.8f;
                    graveProm = graveProm == 0f ? grave : graveProm * 0.94f + grave * 0.06f;
                    for (int i = 0; i < N; i++) {
                        float s = 0f; int c = 0;
                        for (int k = bandaLo[i]; k < bandaHi[i]; k++) { s += magSuav[k]; c++; }
                        float mag = (c > 0) ? s / c : 0f;
                        float db = (float) (20.0 * Math.log10(mag + 1e-7));         // dBFS
                        // cada banda se calibra sola: el techo baja despacio, el piso sube despacio.
                        // asi la voz o la trompeta (mas bajas que el bajo) tambien llenan su barra cuando suenan.
                        if (techo[i] == 0f && piso[i] == 0f && db > -85f) { techo[i] = db + 6f; piso[i] = db - 30f; }
                        if (db > techo[i]) techo[i] = db; else techo[i] -= 0.06f;      // ~3 dB por segundo
                        if (db < piso[i]) piso[i] = db; else piso[i] += 0.04f;
                        if (techo[i] - piso[i] < 24f) piso[i] = techo[i] - 24f;         // rango minimo 24 dB
                        if (techo[i] - piso[i] > 50f) piso[i] = techo[i] - 50f;         // rango maximo 50 dB
                        float v = (db - piso[i]) / (techo[i] + 6f - piso[i]);              // 4 dB de aire: solo los golpes fuertes llenan la barra
                        if (v < 0f) v = 0f; if (v > 1f) v = 1f;
                        v = v * v * (3f - 2f * v);                                     // curva suave: los picos saltan, lo bajo descansa
                        if (db < -85f) v = 0f;                                         // silencio de verdad: la barra no se llena con ruido
                        // golpe: si la banda subio de repente (instrumento que entra), la barra brinca
                        float sube = db - dbAnt[i]; dbAnt[i] = db;
                        if (sube > 4f) golpe[i] = Math.max(golpe[i], Math.min(0.2f, (sube - 4f) * 0.04f));   // brinco suave, solo en golpes claros
                        if (pum && i < 3) golpe[i] = Math.max(golpe[i], 0.15f - i * 0.04f);   // el bombo solo empuja las barras del grave
                        v = Math.min(1f, v + golpe[i]);
                        golpe[i] *= 0.6f;                                             // el empujon se apaga rapido
                        // ataque casi inmediato, caida agil (baila, no se queda pegada)
                        if (v > salida[i]) salida[i] += (v - salida[i]) * 0.55f;   // baja (se estira) con calma
                        else salida[i] += (v - salida[i]) * 0.2f;
                    }
                } else {
                    for (int i = 0; i < N; i++) salida[i] *= 0.85f;
                }
                if (hay) cuadros++;
                try { oyente.niveles(salida); } catch (Throwable e) {}
                long espera = siguiente - System.currentTimeMillis();
                if (espera > 0) Thread.sleep(espera); else siguiente = System.currentTimeMillis();
            }
        } catch (InterruptedException ie) {
        } catch (Throwable t) { motivo = "error al analizar: " + t; if (corriendo) { corriendo = false; try { oyente.fallo(); } catch (Throwable e) {} } }
    }

    // FFT radix-2 en sitio
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }
}
