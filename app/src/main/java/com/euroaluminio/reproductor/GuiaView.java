package com.euroaluminio.reproductor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

// Guia de inicio animada (se ve como un video): 5 pasos que avanzan solos.
// Corre sola como un video (con boton de pausa) y cada cosa dibujada se puede tocar para hacerla de verdad.
// Todo se dibuja a mano (sin imagenes ni video) para que sea liviana en el radio.
public class GuiaView extends View {

    public interface Fin { void terminada(); String accion(String que); void progreso(int paso, long ms, long total, boolean pausado); }   // accion devuelve el texto que se muestra

    private static final float VW = 800f, VH = 480f;      // pantalla virtual (se escala al radio)
    private static final int PASOS = 9;
    private static final long DUR = 9000;                   // ms por paso (avance automatico)
    private static final int AMBAR = 0xFFFFB020;

    private final Fin fin;
    private int paso = 0;
    private long inicioPaso = System.currentTimeMillis();
    private long pausaHasta = 0;
    private boolean pausado = false;          // pausa del "video" (boton play/pausa)
    private long ultimo = System.currentTimeMillis();
    private long inicioAnim = System.currentTimeMillis();
    private final ArrayList<RectF> hitR = new ArrayList<RectF>();
    private final ArrayList<String> hitA = new ArrayList<String>();
    private final RectF btnPlay = new RectF();
    private String aviso = null; private long avisoDesde = 0;
    private String modoReal = null, repReal = null; private boolean tocoAlgo = false;
    // dentro = la guia suena DENTRO del reproductor, como si fuera una cancion:
    // la barra de abajo (play, anterior, siguiente, barra de tiempo) controla la guia
    private boolean dentro = false;
    private boolean acabada = false;
    private long ultimoAviso = 0; private boolean pausaAvisada = false;
    private final RectF btnTerminar = new RectF();
    public void setDentro(boolean d) { dentro = d; }
    public boolean estaPausado() { return pausado; }
    public void playPausa() { pausado = !pausado; pausaHasta = 0; invalidate(); }
    public void siguientePaso() { if (paso < PASOS - 1) ir(paso + 1); }
    public void pasoAnterior() {
        // como en una cancion: si ya avanzo, vuelve al inicio del paso; si no, al paso anterior
        if (System.currentTimeMillis() - inicioPaso > 2500 || paso == 0) ir(paso); else ir(paso - 1);
    }
    public void irA(long ms) {
        int n = (int) Math.max(0, Math.min(PASOS - 1, ms / DUR));
        ir(n); inicioPaso = System.currentTimeMillis() - (ms - n * DUR);
    }
    public static long total() { return PASOS * DUR; }
    private float sc = 1f, ox = 0f, oy = 0f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private RadialGradient fondo;
    private final RectF btnSig = new RectF(), btnAtras = new RectF(), btnSaltar = new RectF();

    private static final String[] LABEL = { "BIENVENIDO", "PASO 1 DE 7", "PASO 2 DE 7", "PASO 3 DE 7", "PASO 4 DE 7", "PASO 5 DE 7", "PASO 6 DE 7", "PASO 7 DE 7", "LISTO" };
    private static final String[][] TITULO = {
        { "Tu música,", "como debe sonar" },
        { "Conecta tu", "memoria USB" },
        { "Escoge cómo", "suena" },
        { "Encuentra", "cualquier canción" },
        { "Guarda tus", "favoritas" },
        { "Ponle", "efectos" },
        { "Carátulas", "automáticas" },
        { "Controla desde", "el celular" },
        { "¡Todo listo!" } };
    private static final String[] TEXTO = {
        "En menos de dos minutos te enseñamos todo lo que hace tu radio.",
        "El radio busca la música solo. Si no aparece:",
        "",
        "Toca Canciones, arriba a la derecha.",
        "",
        "",
        "Con internet, el radio busca las carátulas solo.",
        "",
        "Esta guía no vuelve a salir sola. Para verla de nuevo:" };
    // *texto* = en color ambar
    private static final String[][] LISTA = {
        {},
        { "Ajustes > Música", "Toca *Detectar música del USB*", "O elige la carpeta exacta" },
        { "*En orden*, *Aleat. carpeta* o *todo USB*", "*Sigue* con la próxima o *Repite 1*", "Abajo, a los lados del play" },
        { "*Carpetas*: tal como están en el USB", "*Listas*: las que tú armas", "*Buscar* escribiendo o *por voz*" },
        { "*★* marca la canción que suena", "*+ Lista* la agrega a una lista", "Mantén presionada para más opciones" },
        { "*◉ Vista*: barras, disco o letras", "Las barras bailan con cada instrumento", "*EQ*: ajusta graves y agudos" },
        { "Ajustes > *Carátulas*", "Toca *Descargar carátulas*", "Calidad *Normal*, *Alta* o *Máxima*" },
        { "Prende el *hotspot* del celular", "Conecta el radio a esa red WiFi", "Escanea el *QR* con el celular" },
        { "Ajustes > *Ver guía de inicio*" } };
    private static final String[] NOTA = { "", "", "Siempre arranca en orden, con la siguiente pista.",
        "Desliza la carátula para cambiar de canción.", "Las favoritas quedan guardadas en Listas.", "",
        "También las buscas desde el celular.", "No gasta datos. Cambias canciones, volumen y listas.", "" };

    public GuiaView(Context c, Fin f) {
        super(c);
        fin = f;
        setClickable(true);
        setFocusable(true);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        sc = Math.min(w / VW, h / VH);
        ox = (w - VW * sc) / 2f;
        oy = (h - VH * sc) / 2f;
        fondo = new RadialGradient(w * 0.75f, h * 0.3f, Math.max(w, h) * 0.8f,
            new int[]{ 0xFF2A1D06, 0xFF0D0D14, 0xFF08080C }, new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP);
    }

    private void ir(int nuevo) {
        if (nuevo < 0) nuevo = 0;
        if (nuevo >= PASOS) { terminar(); return; }
        paso = nuevo;
        inicioPaso = System.currentTimeMillis(); inicioAnim = inicioPaso;
        aviso = null; tocoAlgo = false; pausaHasta = 0;
        invalidate();
    }
    private void terminar() { acabada = true; try { if (fin != null) fin.terminada(); } catch (Throwable e) {} }

    // mensaje real que manda la app (ej. "USB: 230 canciones")
    public void aviso(String s) { if (s == null) return; aviso = s; avisoDesde = System.currentTimeMillis(); postInvalidate(); }

    private void hacer(String que) {
        tocoAlgo = true;
        pausaHasta = System.currentTimeMillis() + ("wifi".equals(que) ? 12000 : 7000);   // da tiempo de ver el resultado
        String res = null;
        try { if (fin != null) res = fin.accion(que); } catch (Throwable e) {}
        if (res == null) return;
        if ("modo".equals(que)) modoReal = res;
        else if ("repetir".equals(que)) repReal = res;
        aviso(res);
    }
    private void zona(float l, float t, float rr, float b, String que) { hitR.add(new RectF(l, t, rr, b)); hitA.add(que); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float x = (e.getX() - ox) / sc, y = (e.getY() - oy) / sc;
        if ((!dentro || paso == PASOS - 1) && btnSig.contains(x, y)) { if (paso == PASOS - 1) { hacer("escuchar"); terminar(); } else ir(paso + 1); return true; }
        if (!dentro && paso > 0 && btnAtras.contains(x, y)) { ir(paso - 1); return true; }
        if (!dentro && paso < PASOS - 1 && btnSaltar.contains(x, y)) { terminar(); return true; }
        if (dentro && btnTerminar.contains(x, y)) { terminar(); return true; }
        if (!dentro && btnPlay.contains(x, y)) { pausado = !pausado; pausaHasta = 0; invalidate(); return true; }
        for (int i = hitR.size() - 1; i >= 0; i--) {
            RectF h = hitR.get(i);
            if (x >= h.left - 10 && x <= h.right + 10 && y >= h.top - 10 && y <= h.bottom + 10) {
                String que = hitA.get(i);
                if ("escuchar".equals(que)) { hacer(que); terminar(); }
                else if ("pausa".equals(que)) { playPausa(); tocoAlgo = true; aviso(pausado ? "Guía en pausa · así pausas la música" : "Sigue sonando"); }
                else hacer(que);
                return true;
            }
        }
        pausaHasta = System.currentTimeMillis() + 6000;   // tocar la pantalla da mas tiempo para leer
        return true;
    }

    @Override protected void onDraw(Canvas cv) {
        long now = System.currentTimeMillis();
        // pausa al tocar: el tiempo no corre
        long dt = Math.max(0, Math.min(200, now - ultimo)); ultimo = now;
        if (pausado || pausaHasta > now) inicioPaso += dt;   // el avance se detiene, la animacion sigue
        long t = now - inicioPaso;
        long ta = now - inicioAnim;                           // tiempo de animacion (nunca se congela)
        hitR.clear(); hitA.clear();
        if (paso < PASOS - 1 && t >= DUR) { ir(paso + 1); t = 0; }
        // ultimo paso: al terminar, cierra sola y arranca la musica (auto play)
        if (paso == PASOS - 1 && t >= 7000 && !acabada) { acabada = true; hacer("escuchar"); terminar(); }
        float avance = Math.min(1f, t / (float) DUR);
        if (dentro && fin != null && now - ultimoAviso > 250) {
            ultimoAviso = now;
            try { fin.progreso(paso, paso * DUR + Math.min(t, DUR), PASOS * DUR, pausado); } catch (Throwable ex) {}
        }

        p.setShader(fondo); p.setStyle(Paint.Style.FILL);
        cv.drawRect(0, 0, getWidth(), getHeight(), p);
        p.setShader(null);

        cv.save();
        cv.translate(ox, oy);
        cv.scale(sc, sc);

        // barras de progreso (como historias)
        float gx = 24, gw = (VW - 48 - 6 * (PASOS - 1)) / PASOS;
        for (int i = 0; i < PASOS && !dentro; i++) {
            float x0 = gx + i * (gw + 6);
            p.setColor(0xFF2A2A36); r.set(x0, 14, x0 + gw, 18); cv.drawRoundRect(r, 2, 2, p);
            float f = i < paso ? 1f : (i == paso ? (paso == PASOS - 1 ? 1f : avance) : 0f);
            if (f > 0) { p.setColor(AMBAR); r.set(x0, 14, x0 + gw * f, 18); cv.drawRoundRect(r, 2, 2, p); }
        }
        if (paso < PASOS - 1 && !dentro) {
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(15); pt.setColor(0xFF8B8B9A); pt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("Saltar guía", VW - 24, 46, pt);
            btnSaltar.set(VW - 140, 22, VW, 64);
        } else btnSaltar.setEmpty();

        // entrada suave del contenido
        float e = Math.min(1f, ta / 400f);
        e = 1f - (1f - e) * (1f - e);
        int alfa = (int) (255 * e);
        cv.save();
        cv.translate(30 * (1f - e), 0);
        dibujarTexto(cv, alfa);
        cv.restore();
        cv.save();
        cv.translate(-30 * (1f - e), 0);
        dibujarIlustracion(cv, ta, alfa);
        cv.restore();
        dibujarAviso(cv, now, alfa);

        // botones
        if (dentro) dibujarBotonesDentro(cv, now);
        else {
        String sig = paso == 0 ? "Empezar" : (paso == PASOS - 1 ? "Empezar a escuchar" : "Siguiente");
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(18); pt.setTextAlign(Paint.Align.CENTER);
        float bw = pt.measureText(sig) + 70;
        btnSig.set(VW - 40 - bw, 404, VW - 40, 454);
        float lat = (float) (Math.sin(now / 350.0) * 0.5 + 0.5);   // el boton "respira"
        p.setColor(Color.argb((int) (60 * lat), 255, 176, 32));
        r.set(btnSig.left - 6, btnSig.top - 6, btnSig.right + 6, btnSig.bottom + 6); cv.drawRoundRect(r, 18, 18, p);
        p.setColor(AMBAR); cv.drawRoundRect(btnSig, 14, 14, p);
        pt.setColor(0xFF1A1A1A);
        cv.drawText(sig, btnSig.centerX() - 8, btnSig.centerY() + 6, pt);
        flecha(cv, btnSig.right - 26, btnSig.centerY(), 0xFF1A1A1A);
        if (paso > 0) {
            pt.setColor(0xFF8B8B9A); pt.setTextSize(17); pt.setTextAlign(Paint.Align.LEFT);
            cv.drawText("‹ Atrás", 48, 436, pt);
            btnAtras.set(30, 400, 170, 460);
        } else btnAtras.setEmpty();

        // boton play / pausa del "video"
        float pcx = VW / 2f, pcy = 429;
        btnPlay.set(pcx - 30, pcy - 30, pcx + 30, pcy + 30);
        p.setColor(0xFF1C1C26); cv.drawCircle(pcx, pcy, 24, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0x99FFB020); cv.drawCircle(pcx, pcy, 24, p); p.setStyle(Paint.Style.FILL);
        p.setColor(AMBAR);
        if (pausado) { path.reset(); path.moveTo(pcx - 7, pcy - 11); path.lineTo(pcx + 11, pcy); path.lineTo(pcx - 7, pcy + 11); path.close(); cv.drawPath(path, p); }
        else { cv.drawRect(pcx - 8, pcy - 10, pcx - 3, pcy + 10, p); cv.drawRect(pcx + 3, pcy - 10, pcx + 8, pcy + 10, p); }
        if (pausado) {
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(12); pt.setTextAlign(Paint.Align.CENTER); pt.setColor(0xFF8B8B9A);
            cv.drawText("EN PAUSA", pcx, pcy + 42, pt);
        }
        }

        cv.restore();
        postInvalidateDelayed(33);   // ~30 cuadros por segundo
    }

    // dentro del reproductor: solo "Terminar guía" (y en el ultimo paso, "Empezar a escuchar")
    private void dibujarBotonesDentro(Canvas cv, long now) {
        btnSaltar.setEmpty(); btnAtras.setEmpty(); btnPlay.setEmpty();
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextAlign(Paint.Align.CENTER);
        if (paso == PASOS - 1) {
            String sig = "Empezar a escuchar";
            pt.setTextSize(18);
            float bw = pt.measureText(sig) + 70;
            btnSig.set(VW - 40 - bw, 404, VW - 40, 454);
            float lat = (float) (Math.sin(now / 350.0) * 0.5 + 0.5);
            p.setColor(Color.argb((int) (60 * lat), 255, 176, 32));
            r.set(btnSig.left - 6, btnSig.top - 6, btnSig.right + 6, btnSig.bottom + 6); cv.drawRoundRect(r, 18, 18, p);
            p.setColor(AMBAR); cv.drawRoundRect(btnSig, 14, 14, p);
            pt.setColor(0xFF1A1A1A);
            cv.drawText(sig, btnSig.centerX() - 8, btnSig.centerY() + 6, pt);
            flecha(cv, btnSig.right - 26, btnSig.centerY(), 0xFF1A1A1A);
            btnTerminar.setEmpty();
        } else {
            btnSig.setEmpty();
            pt.setTextSize(14);
            String s = "Terminar guía  ×";
            float w = pt.measureText(s) + 36;
            btnTerminar.set(VW - 40 - w, 412, VW - 40, 448);
            p.setColor(0x661C1C26); cv.drawRoundRect(btnTerminar, 18, 18, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0xFF3A3A48); cv.drawRoundRect(btnTerminar, 18, 18, p); p.setStyle(Paint.Style.FILL);
            pt.setColor(0xFFB8B8C6); cv.drawText(s, btnTerminar.centerX(), btnTerminar.centerY() + 5, pt);
            // pista: la guia se maneja con los botones del reproductor
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(13); pt.setTextAlign(Paint.Align.LEFT); pt.setColor(0xFF8B8B9A);
            cv.drawText(pausado ? "En pausa · toca play abajo para seguir" : "Usa play, anterior y siguiente de abajo", 48, 436, pt);
        }
    }

    private void flecha(Canvas cv, float x, float y, int col) {
        p.setColor(col); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(x - 9, y, x + 6, y, p); cv.drawLine(x + 1, y - 6, x + 7, y, p); cv.drawLine(x + 1, y + 6, x + 7, y, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void dibujarTexto(Canvas cv, int alfa) {
        float x = 48, y = (paso == 0 || paso == PASOS - 1) ? 112 : 96;
        pt.setTextAlign(Paint.Align.LEFT);
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setColor(conAlfa(AMBAR, alfa));
        cv.drawText(LABEL[paso], x, y, pt);
        y += 44;
        pt.setTextSize(36); pt.setColor(conAlfa(0xFFF4F4F8, alfa));
        for (String l : TITULO[paso]) { cv.drawText(l, x, y, pt); y += 40; }
        y += 6;
        if (TEXTO[paso].length() > 0) {
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(17); pt.setColor(conAlfa(0xFFB8B8C6, alfa));
            y = envolver(cv, TEXTO[paso], x, y, 360, 24) + 8;
        }
        for (int i = 0; i < LISTA[paso].length; i++) {
            float cy = y + 6;
            p.setColor(conAlfa(0xFF2E2614, alfa)); cv.drawCircle(x + 13, cy, 13, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(conAlfa(0x66FFB020, alfa)); cv.drawCircle(x + 13, cy, 13, p); p.setStyle(Paint.Style.FILL);
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setColor(conAlfa(AMBAR, alfa)); pt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(LISTA[paso].length == 1 && paso == PASOS - 1 ? "+" : String.valueOf(i + 1), x + 13, cy + 5, pt);
            pt.setTextAlign(Paint.Align.LEFT);
            lineaMarcada(cv, LISTA[paso][i], x + 38, cy + 6, alfa);
            y += 36;
        }
        if (NOTA[paso].length() > 0) {
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(15); pt.setColor(conAlfa(0xFFB8B8C6, alfa));
            envolver(cv, NOTA[paso], x, y + 8, 360, 21);
        }
    }

    // dibuja "texto *resaltado* texto" con las partes entre asteriscos en ambar
    private void lineaMarcada(Canvas cv, String s, float x, float y, int alfa) {
        pt.setTextSize(16);
        String[] partes = s.split("\\*", -1);
        for (int i = 0; i < partes.length; i++) {
            boolean marca = (i % 2) == 1;
            pt.setTypeface(marca ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pt.setColor(conAlfa(marca ? AMBAR : 0xFFE4E4EC, alfa));
            cv.drawText(partes[i], x, y, pt);
            x += pt.measureText(partes[i]);
        }
    }

    private float envolver(Canvas cv, String s, float x, float y, float ancho, float alto) {
        String[] pal = s.split(" ");
        StringBuilder l = new StringBuilder();
        for (String w : pal) {
            String prueba = l.length() == 0 ? w : l + " " + w;
            if (pt.measureText(prueba) > ancho && l.length() > 0) { cv.drawText(l.toString(), x, y, pt); y += alto; l = new StringBuilder(w); }
            else { l = new StringBuilder(prueba); }
        }
        if (l.length() > 0) { cv.drawText(l.toString(), x, y, pt); y += alto; }
        return y;
    }

    private static int conAlfa(int c, int a) { return Color.argb((Color.alpha(c) * a) / 255, Color.red(c), Color.green(c), Color.blue(c)); }

    // pastilla con el resultado real, o la pista "Toca para probar"
    private void dibujarAviso(Canvas cv, long now, int alfa) {
        float cx = 600, y = 384;
        pt.setTextAlign(Paint.Align.CENTER);
        if (aviso != null && now - avisoDesde < 6000) {
            float a = Math.min(1f, (now - avisoDesde) / 250f);
            float fade = now - avisoDesde > 5400 ? (6000 - (now - avisoDesde)) / 600f : 1f;
            int al = (int) (alfa * a * fade);
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(15);
            float w = pt.measureText(aviso) + 48;
            p.setColor(Color.argb((int) (al * 0.95f), 22, 52, 34)); r.set(cx - w / 2, y - 22, cx + w / 2, y + 12); cv.drawRoundRect(r, 17, 17, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(Color.argb(al, 78, 203, 122)); cv.drawRoundRect(r, 17, 17, p); p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(al, 78, 203, 122)); cv.drawCircle(cx - w / 2 + 18, y - 5, 4, p);
            pt.setColor(Color.argb(al, 220, 255, 230)); cv.drawText(aviso, cx + 6, y, pt);
        } else if (!hitR.isEmpty() && paso > 0 && paso < PASOS - 1) {
            float lat = (float) (Math.sin(now / 400.0) * 0.5 + 0.5);
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13);
            pt.setColor(Color.argb((int) (alfa * (0.55f + 0.45f * lat)), 255, 176, 32));
            cv.drawText("TOCA PARA PROBARLO DE VERDAD", cx, y, pt);
        }
    }

    // ---------------- ilustraciones animadas ----------------
    private void dibujarIlustracion(Canvas cv, long t, int alfa) {
        float cx = 600, cy = 228;
        switch (paso) {
            case 0: disco(cv, cx, cy, t, alfa); break;
            case 1: tarjetaUsb(cv, cx, cy, t, alfa); break;
            case 2: barraModos(cv, cx, cy, t, alfa); break;
            case 3: listaCanciones(cv, cx, cy, t, alfa); break;
            case 4: favoritos(cv, cx, cy, t, alfa); break;
            case 5: vistas(cv, cx, cy, t, alfa); break;
            case 6: caratulas(cv, cx, cy, t, alfa); break;
            case 7: radioCelular(cv, cx, cy, t, alfa); break;
            default: listo(cv, cx, cy, t, alfa); break;
        }
    }

    private void tarjeta(Canvas cv, float l, float tp, float rr, float b, int alfa) {
        p.setColor(conAlfa(0xFF1C1C26, alfa)); r.set(l, tp, rr, b); cv.drawRoundRect(r, 18, 18, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(conAlfa(0xFF2A2A38, alfa)); cv.drawRoundRect(r, 18, 18, p); p.setStyle(Paint.Style.FILL);
    }

    // toque animado: circulo que crece y se desvanece (en vez de la manito)
    private void toque(Canvas cv, float x, float y, long t, int alfa) {
        float ciclo = (t % 1600) / 1600f;
        float rad = 10 + 30 * ciclo;
        int a = (int) (alfa * (1f - ciclo));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.argb(a, 255, 255, 255)); cv.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.argb((int) (alfa * 0.85f), 255, 255, 255)); cv.drawCircle(x, y, 9, p);
    }

    private void disco(Canvas cv, float cx, float cy, long t, int alfa) {
        float R = 140;
        zona(cx - R, cy - R, cx + R, cy + R, dentro ? "pausa" : "play");
        p.setColor(conAlfa(0xFF0A0A0A, alfa)); cv.drawCircle(cx, cy, R, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1);
        for (float rr = 70; rr < R - 8; rr += 7) { p.setColor(conAlfa(0x14FFFFFF, alfa)); cv.drawCircle(cx, cy, rr, p); }
        p.setStyle(Paint.Style.FILL);
        cv.save(); cv.rotate((t / 1000f) * 120f, cx, cy);   // gira
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(10); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(conAlfa(0x30FFFFFF, alfa));
        r.set(cx - 118, cy - 118, cx + 118, cy + 118); cv.drawArc(r, 200, 50, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(cx, cy, 56, p);
        p.setColor(conAlfa(0xFFD0601A, alfa)); r.set(cx - 56, cy - 56, cx + 56, cy + 56); cv.drawArc(r, 0, 180, true, p);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(cx, cy, 40, p);
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(14); pt.setTextAlign(Paint.Align.CENTER); pt.setColor(conAlfa(0xFF1A1A1A, alfa));
        cv.drawText("JFV", cx, cy - 18, pt);
        cv.restore();
        p.setColor(conAlfa(0xFF08080C, alfa)); cv.drawCircle(cx, cy, 7, p);
        // ondas de sonido saliendo del disco
        for (int i = 0; i < 3; i++) {
            float f = ((t / 1400f) + i / 3f) % 1f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.argb((int) (alfa * 0.35f * (1f - f)), 255, 176, 32));
            cv.drawCircle(cx, cy, R + 6 + f * 40, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void tarjetaUsb(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 165, tp = cy - 110, rr = cx + 165, b = cy + 120;
        tarjeta(cv, l, tp, rr, b, alfa);
        pt.setTextAlign(Paint.Align.LEFT); pt.setTypeface(Typeface.DEFAULT_BOLD);
        pt.setTextSize(12); pt.setColor(conAlfa(AMBAR, alfa)); cv.drawText("MÚSICA", l + 20, tp + 30, pt);
        pt.setTextSize(16); pt.setColor(conAlfa(0xFFF4F4F8, alfa)); cv.drawText("Vincular carpeta de música", l + 20, tp + 56, pt);
        pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(12); pt.setColor(conAlfa(0xFF8B8B9A, alfa)); cv.drawText("Solo mostrará esa carpeta", l + 20, tp + 76, pt);
        // boton secundario
        p.setColor(conAlfa(0xFF1F1A0A, alfa)); r.set(l + 20, tp + 92, rr - 20, tp + 130); cv.drawRoundRect(r, 10, 10, p);
        p.setStyle(Paint.Style.STROKE); p.setColor(conAlfa(0x66FFB020, alfa)); cv.drawRoundRect(r, 10, 10, p); p.setStyle(Paint.Style.FILL);
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(14); pt.setTextAlign(Paint.Align.CENTER); pt.setColor(conAlfa(AMBAR, alfa));
        cv.drawText("Elegir carpeta", cx, tp + 116, pt);
        zona(l + 20, tp + 92, rr - 20, tp + 130, "carpeta");
        zona(l + 14, tp + 138, rr - 14, tp + 196, "usb");
        // boton principal que "se presiona"
        float ciclo = (t % 1600) / 1600f;
        boolean apretado = ciclo < 0.18f;
        float k = apretado ? 4 : 0;
        float lat = (float) (Math.sin(t / 300.0) * 0.5 + 0.5);
        p.setColor(Color.argb((int) (alfa * (0.25f + 0.35f * lat)), 255, 176, 32));
        r.set(l + 14, tp + 138, rr - 14, tp + 196); cv.drawRoundRect(r, 14, 14, p);
        p.setColor(conAlfa(AMBAR, alfa)); r.set(l + 20 + k, tp + 144 + k, rr - 20 - k, tp + 190 - k); cv.drawRoundRect(r, 10, 10, p);
        pt.setColor(conAlfa(0xFF1A1A1A, alfa)); pt.setTextSize(15);
        cv.drawText("Detectar música del USB", cx, tp + 172, pt);
        // resultado que aparece despues del toque
        if (t > 1800 && !tocoAlgo) {
            int a2 = (int) (alfa * Math.min(1f, (t - 1800) / 500f));
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(13); pt.setColor(Color.argb(a2, 78, 203, 122));
            cv.drawText("Listo: música del USB cargada", cx, tp + 216, pt);
        }
        toque(cv, rr - 32, tp + 182, t, alfa);
    }

    private void barraModos(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 180, tp = cy - 46, rr = cx + 160, b = cy + 46;
        tarjeta(cv, l, tp, rr, b, alfa);
        String[] modos = { "En orden", "Aleat. carpeta", "Aleat. todo USB" };
        int m = (int) ((t / 1600) % 3);
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setTextAlign(Paint.Align.CENTER);
        float bx = l + 12, w1 = 120;                        // espacio fijo para el modo (no se monta)
        float lat = (float) (Math.sin(t / 300.0) * 0.5 + 0.5);
        p.setColor(Color.argb((int) (alfa * (0.25f + 0.35f * lat)), 255, 176, 32)); r.set(bx - 4, cy - 22, bx + w1 + 4, cy + 22); cv.drawRoundRect(r, 12, 12, p);
        p.setColor(conAlfa(0xFF2E2614, alfa)); r.set(bx, cy - 18, bx + w1, cy + 18); cv.drawRoundRect(r, 10, 10, p);
        pt.setColor(conAlfa(AMBAR, alfa)); cv.drawText(modoReal != null ? modoReal : modos[m], bx + w1 / 2, cy + 5, pt);
        zona(bx, cy - 22, bx + w1, cy + 22, "modo");
        float px = bx + w1 + 70;
        p.setColor(conAlfa(0xFFF4F4F8, alfa));
        tri(cv, px - 46, cy, -1); tri(cv, px + 46, cy, 1);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(px, cy, 26, p);
        zona(px - 28, cy - 28, px + 28, cy + 28, dentro ? "pausa" : "play");
        if (!dentro) {
            zona(px - 58, cy - 20, px - 34, cy + 20, "anterior");
            zona(px + 34, cy - 20, px + 58, cy + 20, "siguiente");
        }
        p.setColor(conAlfa(0xFF1A1A1A, alfa)); cv.drawRect(px - 9, cy - 10, px - 3, cy + 10, p); cv.drawRect(px + 3, cy - 10, px + 9, cy + 10, p);
        p.setColor(conAlfa(0xFF262633, alfa)); r.set(rr - 66, cy - 18, rr - 12, cy + 18); cv.drawRoundRect(r, 10, 10, p);
        boolean rep1 = "Repite 1".equals(repReal);
        pt.setColor(conAlfa(rep1 ? AMBAR : 0xFFF4F4F8, alfa)); pt.setTextSize(rep1 ? 11 : 13);
        cv.drawText(repReal != null ? repReal : "Sigue", rr - 39, cy + 5, pt); pt.setTextSize(13);
        zona(rr - 66, cy - 18, rr - 12, cy + 18, "repetir");
        toque(cv, bx + w1 / 2, cy + 26, t, alfa);
    }
    private void tri(Canvas cv, float x, float y, int dir) {
        path.reset();
        path.moveTo(x - 7 * dir, y - 9); path.lineTo(x + 5 * dir, y); path.lineTo(x - 7 * dir, y + 9); path.close();
        cv.drawPath(path, p);
        cv.drawRect(dir > 0 ? x + 6 : x - 9, y - 9, dir > 0 ? x + 9 : x - 6, y + 9, p);
    }

    private void radioCelular(Canvas cv, float cx, float cy, long t, int alfa) {
        // radio con QR
        float l = cx - 180, tp = cy - 60;
        zona(l, tp, l + 180, tp + 112, "wifi");
        p.setColor(conAlfa(0xFF1C1C26, alfa)); r.set(l, tp, l + 180, tp + 112); cv.drawRoundRect(r, 12, 12, p);
        p.setColor(conAlfa(0xFF0D0D14, alfa)); r.set(l + 12, tp + 12, l + 168, tp + 100); cv.drawRoundRect(r, 6, 6, p);
        float qx = l + 58, qy = tp + 24;
        p.setColor(conAlfa(0xFFFFFFFF, alfa)); cv.drawRect(qx, qy, qx + 64, qy + 64, p);
        p.setColor(conAlfa(0xFF111111, alfa));
        cv.drawRect(qx + 4, qy + 4, qx + 20, qy + 20, p); cv.drawRect(qx + 44, qy + 4, qx + 60, qy + 20, p); cv.drawRect(qx + 4, qy + 44, qx + 20, qy + 60, p);
        cv.drawRect(qx + 26, qy + 10, qx + 34, qy + 18, p); cv.drawRect(qx + 34, qy + 26, qx + 46, qy + 32, p); cv.drawRect(qx + 26, qy + 38, qx + 32, qy + 50, p); cv.drawRect(qx + 46, qy + 40, qx + 56, qy + 50, p);
        pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(13); pt.setTextAlign(Paint.Align.CENTER); pt.setColor(conAlfa(0xFF8B8B9A, alfa));
        cv.drawText("RADIO", l + 90, tp + 132, pt);
        // ondas WiFi que van del celular al radio
        for (int i = 0; i < 3; i++) {
            float f = ((t / 900f) + i / 3f) % 1f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.argb((int) (alfa * (1f - f)), 255, 176, 32));
            float rad = 14 + f * 46;
            r.set(cx + 70 - rad, cy - rad, cx + 70 + rad, cy + rad); cv.drawArc(r, 140, 80, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        // celular
        float fx = cx + 92, fy = cy - 80;
        zona(fx, fy, fx + 74, fy + 144, "wifi");
        p.setColor(conAlfa(0xFF1C1C26, alfa)); r.set(fx, fy, fx + 74, fy + 144); cv.drawRoundRect(r, 12, 12, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(conAlfa(AMBAR, alfa)); cv.drawRoundRect(r, 12, 12, p); p.setStyle(Paint.Style.FILL);
        p.setColor(conAlfa(0xFF0D0D14, alfa)); r.set(fx + 8, fy + 14, fx + 66, fy + 122); cv.drawRoundRect(r, 4, 4, p);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(fx + 37, fy + 44, 12, p);
        float vol = 0.3f + 0.7f * (float) (Math.sin(t / 500.0) * 0.5 + 0.5);   // barra de volumen que se mueve
        p.setColor(conAlfa(0xFF2A2A36, alfa)); r.set(fx + 14, fy + 66, fx + 60, fy + 74); cv.drawRoundRect(r, 4, 4, p);
        p.setColor(conAlfa(AMBAR, alfa)); r.set(fx + 14, fy + 66, fx + 14 + 46 * vol, fy + 74); cv.drawRoundRect(r, 4, 4, p);
        p.setColor(conAlfa(0xFF2A2A36, alfa)); r.set(fx + 14, fy + 82, fx + 60, fy + 88); cv.drawRoundRect(r, 3, 3, p); r.set(fx + 14, fy + 96, fx + 60, fy + 102); cv.drawRoundRect(r, 3, 3, p);
        cv.drawText("CELULAR", fx + 37, fy + 164, pt);
    }

    // ----- Canciones: pestañas, buscador con microfono y filas -----
    private void listaCanciones(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 170, tp = cy - 118, rr = cx + 170, b = cy + 118;
        tarjeta(cv, l, tp, rr, b, alfa);
        zona(l, tp + 40, rr, b, "canciones");
        String[] tabs = { "Carpetas", "Listas", "Videos" };
        int act = (int) ((t / 2200) % 3);
        float tw = (rr - l - 24) / 3f;
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 3; i++) {
            float x0 = l + 12 + i * tw;
            p.setColor(conAlfa(i == act ? 0xFF262633 : 0xFF1C1C26, alfa)); r.set(x0, tp + 12, x0 + tw - 4, tp + 40); cv.drawRoundRect(r, 8, 8, p);
            pt.setColor(conAlfa(i == act ? AMBAR : 0xFF8B8B9A, alfa)); cv.drawText(tabs[i], x0 + tw / 2 - 2, tp + 31, pt);
        }
        // buscador que se escribe solo
        p.setColor(conAlfa(0xFF0D0D14, alfa)); r.set(l + 12, tp + 50, rr - 56, tp + 84); cv.drawRoundRect(r, 10, 10, p);
        String palabra = "bachata";
        int n = (int) Math.min(palabra.length(), Math.max(0, ((t % 4400) - 400) / 220));
        pt.setTypeface(Typeface.DEFAULT); pt.setTextAlign(Paint.Align.LEFT); pt.setTextSize(14);
        pt.setColor(conAlfa(n == 0 ? 0xFF6A6A78 : 0xFFF4F4F8, alfa));
        String vis = n == 0 ? "Buscar canción…" : palabra.substring(0, n);
        cv.drawText(vis, l + 24, tp + 72, pt);
        if (n > 0 && (t / 400) % 2 == 0) { p.setColor(conAlfa(AMBAR, alfa)); float cxr = l + 26 + pt.measureText(vis); cv.drawRect(cxr, tp + 58, cxr + 2, tp + 76, p); }
        // microfono
        float mx = rr - 34, my = tp + 67;
        float lat = (float) (Math.sin(t / 300.0) * 0.5 + 0.5);
        p.setColor(Color.argb((int) (alfa * (0.2f + 0.3f * lat)), 255, 176, 32)); cv.drawCircle(mx, my, 20, p);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(mx, my, 16, p);
        p.setColor(conAlfa(0xFF1A1A1A, alfa)); r.set(mx - 4, my - 9, mx + 4, my + 3); cv.drawRoundRect(r, 4, 4, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); r.set(mx - 7, my - 5, mx + 7, my + 6); cv.drawArc(r, 0, 180, false, p);
        cv.drawLine(mx, my + 6, mx, my + 10, p); p.setStyle(Paint.Style.FILL);
        zona(mx - 20, my - 20, mx + 20, my + 20, "voz");
        // filas
        String[][] filas = { { "Bachatas", "48 canciones" }, { "Merengue clásico", "62 canciones" }, { "Salsa 90", "35 canciones" } };
        for (int i = 0; i < 3; i++) {
            float y0 = tp + 96 + i * 44;
            float ap = Math.max(0f, Math.min(1f, (t - 300 - i * 200) / 400f));
            int a2 = (int) (alfa * ap);
            p.setColor(conAlfa(0xFF2E2614, a2)); r.set(l + 14, y0 + 4, l + 44, y0 + 34); cv.drawRoundRect(r, 6, 6, p);
            p.setColor(conAlfa(AMBAR, a2)); r.set(l + 20, y0 + 12, l + 38, y0 + 28); cv.drawRoundRect(r, 2, 2, p); cv.drawRect(l + 20, y0 + 9, l + 29, y0 + 13, p);
            pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(14); pt.setColor(conAlfa(0xFFF4F4F8, a2)); cv.drawText(filas[i][0], l + 56, y0 + 18, pt);
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(11); pt.setColor(conAlfa(0xFF8B8B9A, a2)); cv.drawText(filas[i][1], l + 56, y0 + 33, pt);
        }
        toque(cv, mx, my, t, alfa);
    }

    // ----- Favoritas: la cancion que suena, estrella y + Lista -----
    private void favoritos(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 170, tp = cy - 70, rr = cx + 170, b = cy + 70;
        tarjeta(cv, l, tp, rr, b, alfa);
        // caratula
        p.setColor(conAlfa(0xFFD0601A, alfa)); r.set(l + 16, tp + 16, l + 92, tp + 92); cv.drawRoundRect(r, 10, 10, p);
        p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(l + 54, tp + 54, 24, p);
        p.setColor(conAlfa(0xFF1A1A1A, alfa)); cv.drawCircle(l + 54, tp + 54, 5, p);
        pt.setTextAlign(Paint.Align.LEFT); pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(15); pt.setColor(conAlfa(0xFFF4F4F8, alfa));
        cv.drawText("Canción que suena", l + 106, tp + 42, pt);
        pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(12); pt.setColor(conAlfa(0xFF8B8B9A, alfa)); cv.drawText("Artista", l + 106, tp + 62, pt);
        // barrita de progreso
        float f = (t % 6000) / 6000f;
        p.setColor(conAlfa(0xFF2A2A36, alfa)); r.set(l + 106, tp + 76, rr - 18, tp + 80); cv.drawRoundRect(r, 2, 2, p);
        p.setColor(conAlfa(AMBAR, alfa)); r.set(l + 106, tp + 76, l + 106 + (rr - 18 - l - 106) * f, tp + 80); cv.drawRoundRect(r, 2, 2, p);
        // estrella que se llena
        float sx = l + 60, sy = tp + 118;
        boolean llena = (t % 3200) > 1100;
        float esc = llena ? 1f + 0.25f * (float) Math.max(0, 1 - ((t % 3200) - 1100) / 300.0) : 1f;
        p.setColor(conAlfa(0xFF262633, alfa)); r.set(sx - 40, sy - 18, sx + 40, sy + 18); cv.drawRoundRect(r, 10, 10, p);
        estrella(cv, sx, sy, 11 * esc, llena, alfa);
        zona(sx - 40, sy - 18, sx + 40, sy + 18, "fav");
        // + Lista
        float bx = l + 120;
        p.setColor(conAlfa(0xFF262633, alfa)); r.set(bx, sy - 18, bx + 96, sy + 18); cv.drawRoundRect(r, 10, 10, p);
        pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setTextAlign(Paint.Align.CENTER); pt.setColor(conAlfa(0xFFF4F4F8, alfa));
        cv.drawText("+ Lista", bx + 48, sy + 5, pt);
        zona(bx, sy - 18, bx + 96, sy + 18, "lista");
        if (llena) {
            int a2 = (int) (alfa * Math.min(1f, ((t % 3200) - 1100) / 300f));
            pt.setTypeface(Typeface.DEFAULT); pt.setTextSize(12); pt.setTextAlign(Paint.Align.LEFT); pt.setColor(Color.argb(a2, 78, 203, 122));
            cv.drawText("Guardada", bx + 108, sy + 5, pt);
        }
        toque(cv, sx + 6, sy + 6, t, alfa);
    }
    private void estrella(Canvas cv, float x, float y, float R, boolean llena, int alfa) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5;
            float rad = (i % 2 == 0) ? R : R * 0.45f;
            float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        if (llena) { p.setColor(conAlfa(AMBAR, alfa)); cv.drawPath(path, p); }
        else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(conAlfa(0xFFF4F4F8, alfa)); cv.drawPath(path, p); p.setStyle(Paint.Style.FILL); }
    }

    // ----- Vistas: barras que bailan por instrumento + boton EQ -----
    private void vistas(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 170, tp = cy - 110, rr = cx + 170, b = cy + 110;
        tarjeta(cv, l, tp, rr, b, alfa);
        String[] nombres = { "Barras", "Disco", "Letras" };
        int v = (int) ((t / 2600) % 3);
        pt.setTextAlign(Paint.Align.LEFT); pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(12); pt.setColor(conAlfa(AMBAR, alfa));
        cv.drawText("◉ VISTA: " + nombres[v].toUpperCase(), l + 18, tp + 28, pt);
        zona(l, tp, rr, b - 50, "vista");
        float zx = l + 18, zy = tp + 44, zw = rr - l - 36, zh = 110;
        if (v == 0) {
            int nb = 14; float bw = zw / nb;
            for (int i = 0; i < nb; i++) {
                double f = 0.5 + 0.5 * Math.sin(t / (140.0 + i * 23) + i * 1.7) * Math.cos(t / 900.0 + i);
                float h = (float) (12 + (zh - 12) * Math.abs(f));
                p.setColor(conAlfa(i < 4 ? 0xFFD0601A : (i < 10 ? AMBAR : 0xFFFFD27A), alfa));
                r.set(zx + i * bw + 2, zy + zh - h, zx + (i + 1) * bw - 2, zy + zh); cv.drawRoundRect(r, 3, 3, p);
            }
        } else if (v == 1) {
            float dx = zx + zw / 2, dy = zy + zh / 2;
            p.setColor(conAlfa(0xFF0A0A0A, alfa)); cv.drawCircle(dx, dy, 52, p);
            cv.save(); cv.rotate(t / 1000f * 150f, dx, dy);
            p.setColor(conAlfa(AMBAR, alfa)); cv.drawCircle(dx, dy, 22, p);
            p.setColor(conAlfa(0xFFD0601A, alfa)); r.set(dx - 22, dy - 22, dx + 22, dy + 22); cv.drawArc(r, 0, 180, true, p);
            cv.restore();
            p.setColor(conAlfa(0xFF08080C, alfa)); cv.drawCircle(dx, dy, 4, p);
        } else {
            String[] lin = { "Y bailamos", "toda la noche", "en la carretera" };
            int k = (int) ((t / 800) % 3);
            pt.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 3; i++) {
                pt.setTypeface(i == k ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); pt.setTextSize(i == k ? 20 : 15);
                pt.setColor(conAlfa(i == k ? 0xFFF4F4F8 : 0xFF6A6A78, alfa));
                cv.drawText(lin[i], zx + zw / 2, zy + 28 + i * 36, pt);
            }
        }
        // botones Vista y EQ
        float by = b - 34;
        p.setColor(conAlfa(0xFF262633, alfa)); r.set(l + 18, by - 16, l + 128, by + 16); cv.drawRoundRect(r, 10, 10, p);
        pt.setTextAlign(Paint.Align.CENTER); pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13); pt.setColor(conAlfa(0xFFF4F4F8, alfa));
        cv.drawText("◉ Vista", l + 73, by + 5, pt);
        zona(l + 18, by - 16, l + 128, by + 16, "vista");
        p.setColor(conAlfa(0xFF262633, alfa)); r.set(rr - 98, by - 16, rr - 18, by + 16); cv.drawRoundRect(r, 10, 10, p);
        cv.drawText("EQ", rr - 58, by + 5, pt);
        zona(rr - 98, by - 16, rr - 18, by + 16, "eq");
        toque(cv, l + 73, by + 4, t, alfa);
    }

    // ----- Caratulas que bajan solas -----
    private void caratulas(Canvas cv, float cx, float cy, long t, int alfa) {
        float l = cx - 170, tp = cy - 100, rr = cx + 170, b = cy + 100;
        tarjeta(cv, l, tp, rr, b, alfa);
        zona(l, tp, rr, b, "caratulas");
        int[][] cols = { { 0xFFD0601A, 0xFFFFB020 }, { 0xFF2E6FD0, 0xFF7AC0FF }, { 0xFF8A2ED0, 0xFFE07AFF } };
        float w = 86, gap = (rr - l - 3 * w) / 4f;
        long c = t % 5200;
        for (int i = 0; i < 3; i++) {
            float x0 = l + gap + i * (w + gap), y0 = tp + 22;
            float f = Math.max(0f, Math.min(1f, (c - 400 - i * 700) / 500f));
            p.setColor(conAlfa(0xFF0D0D14, alfa)); r.set(x0, y0, x0 + w, y0 + w); cv.drawRoundRect(r, 10, 10, p);
            if (f > 0) {
                int a2 = (int) (alfa * f);
                p.setColor(conAlfa(cols[i][0], a2)); r.set(x0, y0, x0 + w, y0 + w); cv.drawRoundRect(r, 10, 10, p);
                p.setColor(conAlfa(cols[i][1], a2)); cv.drawCircle(x0 + w / 2, y0 + w / 2, 24 * f, p);
                p.setColor(conAlfa(0xFF1A1A1A, a2)); cv.drawCircle(x0 + w / 2, y0 + w / 2, 5, p);
            } else {
                // flecha de descarga
                p.setColor(conAlfa(0xFF3A3A48, alfa)); float ax = x0 + w / 2, ay = y0 + w / 2;
                cv.drawRect(ax - 3, ay - 16, ax + 3, ay + 2, p);
                path.reset(); path.moveTo(ax - 11, ay); path.lineTo(ax + 11, ay); path.lineTo(ax, ay + 12); path.close(); cv.drawPath(path, p);
            }
        }
        // barra de descarga
        float prog = Math.min(1f, c / 2900f);
        float py = tp + 136;
        p.setColor(conAlfa(0xFF2A2A36, alfa)); r.set(l + 22, py, rr - 22, py + 6); cv.drawRoundRect(r, 3, 3, p);
        p.setColor(conAlfa(AMBAR, alfa)); r.set(l + 22, py, l + 22 + (rr - l - 44) * prog, py + 6); cv.drawRoundRect(r, 3, 3, p);
        pt.setTextAlign(Paint.Align.CENTER); pt.setTypeface(Typeface.DEFAULT_BOLD); pt.setTextSize(13);
        pt.setColor(conAlfa(prog >= 1f ? 0xFF4ECB7A : 0xFFB8B8C6, alfa));
        cv.drawText(prog >= 1f ? "3 carátulas nuevas" : "Descargando carátulas…", cx, py + 32, pt);
    }

    private void listo(Canvas cv, float cx, float cy, long t, int alfa) {
        float R = 96;
        zona(cx - R, cy - R, cx + R, cy + R, "escuchar");
        p.setColor(conAlfa(0xFF2E2614, alfa)); cv.drawCircle(cx, cy, R, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setColor(conAlfa(AMBAR, alfa));
        float aro = Math.min(1f, t / 700f);
        r.set(cx - R, cy - R, cx + R, cy + R); cv.drawArc(r, -90, 360 * aro, false, p);
        // la palomita se dibuja sola
        float f = Math.max(0f, Math.min(1f, (t - 500) / 600f));
        p.setStrokeWidth(14); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        float ax = cx - 46, ay = cy + 2, bx = cx - 14, by = cy + 34, cx2 = cx + 48, cy2 = cy - 32;
        float f1 = Math.min(1f, f * 2f), f2 = Math.max(0f, f * 2f - 1f);
        if (f1 > 0) cv.drawLine(ax, ay, ax + (bx - ax) * f1, ay + (by - ay) * f1, p);
        if (f2 > 0) cv.drawLine(bx, by, bx + (cx2 - bx) * f2, by + (cy2 - by) * f2, p);
        p.setStyle(Paint.Style.FILL);
        // destellos
        if (t > 1100) {
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4 + t / 2000.0;
                float d = R + 18 + (float) (Math.sin(t / 250.0 + i) * 6);
                int a = (int) (alfa * 0.6f);
                p.setColor(Color.argb(a, 255, 176, 32));
                cv.drawCircle(cx + (float) Math.cos(ang) * d, cy + (float) Math.sin(ang) * d, 4, p);
            }
        }
    }
}
