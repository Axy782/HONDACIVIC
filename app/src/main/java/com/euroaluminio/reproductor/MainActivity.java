package com.euroaluminio.reproductor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.content.ComponentName;
import android.media.audiofx.Visualizer;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

public class MainActivity extends Activity {

    static MainActivity activo;
    private ComponentName mbCn;

    static class Song {
        long id, albumId, fecha;
        String title, artist, album, path;
        int dur;
    }

    static class Carpeta {
        String name, path;
        boolean esLista = false;
        ArrayList<Song> songs = new ArrayList<Song>();
    }

    private final ArrayList<Song> songs = new ArrayList<Song>();     // lista que SUENA (activa)
    private final ArrayList<Integer> order = new ArrayList<Integer>();
    private int posEnOrden = -1;

    private final ArrayList<Carpeta> carpetas = new ArrayList<Carpeta>();  // todas las carpetas con música
    private ArrayList<Song> cancionesCarpeta = new ArrayList<Song>();       // canciones de la carpeta abierta
    private int modo = 0;                 // 0 = ver carpetas, 1 = ver canciones de una carpeta
    private Carpeta carpetaAbierta = null;

    private MediaPlayer mp;
    private Equalizer eq;
    private AudioManager am;
    private AudioManager.OnAudioFocusChangeListener focoListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int f) {
            try {
                if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    // Interrupción TEMPORAL (ej. cámara de reversa): bajar a 50% pero SEGUIR sonando (no pausar, no reiniciar)
                    if (mp != null && prepared) { try { mp.setVolume(0.5f, 0.5f); } catch (Exception e) {} }
                } else if (f == AudioManager.AUDIOFOCUS_GAIN) {
                    // Recuperamos el audio: volver al volumen normal
                    if (mp != null && prepared) { try { mp.setVolume(1f, 1f); } catch (Exception e) {} }
                } else if (f == AudioManager.AUDIOFOCUS_LOSS) {
                    // Pérdida permanente (otra app tomó el audio): sí pausar
                    if (mp != null && prepared && mp.isPlaying()) { mp.pause(); pintarPlay(false); }
                }
            } catch (Exception e) {}
        }
    };
    // Instala Conscrypt (TLS moderno con BoringSSL, como Chrome/Poweramp) como proveedor #1.
    // Así el radio viejo puede hacer el saludo de seguridad con servidores actuales (Apple/Deezer).
    private void instalarConscrypt() {
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1);
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            conscryptFactory = sc.getSocketFactory();
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(conscryptFactory);
            conscryptOk = true;
        } catch (Throwable t) { conscryptOk = false; }
    }
    private boolean conscryptOk = false;
    private javax.net.ssl.SSLSocketFactory conscryptFactory = null;

    // Detecta si el radio está en mute/silencio (por hardware o volumen en 0)
    private boolean estaEnMute() {
        try {
            java.lang.reflect.Method m = AudioManager.class.getMethod("isStreamMute", int.class);
            Object r = m.invoke(am, AudioManager.STREAM_MUSIC);
            if (r instanceof Boolean && ((Boolean) r).booleanValue()) return true;
        } catch (Exception e) {}
        try { if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return true; } catch (Exception e) {}
        return false;
    }
    // ---- Recibir canciones del celular por WiFi ----
    private ServidorWifi servidor;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.os.PowerManager.WakeLock wakeWifi;
    private void soltarCandadosWifi() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception e) {}
        try { if (wakeWifi != null && wakeWifi.isHeld()) wakeWifi.release(); } catch (Exception e) {}
        wifiLock = null; wakeWifi = null;
    }
    private void tomarCandadosWifi() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                int modo = android.net.wifi.WifiManager.WIFI_MODE_FULL;
                try { modo = android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF; } catch (Throwable t) {}
                wifiLock = wm.createWifiLock(modo, "SonidoJFV:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();   // mantiene el WiFi despierto aunque manejes o se apague la pantalla
            }
        } catch (Exception e) {}
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wakeWifi = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SonidoJFV:wifisrv");
            wakeWifi.setReferenceCounted(false);
            wakeWifi.acquire();
        } catch (Exception e) {}
    }
    // Abre la pagina OFICIAL de YouTube en el navegador del radio (legal, sin apps raras)
    private void abrirYouTube() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://m.youtube.com"));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(this, "Este radio no tiene navegador para abrir YouTube", Toast.LENGTH_LONG).show();
        }
    }
    private void alternarServidorWifi() {
        if (servidor != null && servidor.activo()) {
            servidor.detener(); servidor = null;
            soltarCandadosWifi();
            Toast.makeText(this, "WiFi apagado", Toast.LENGTH_SHORT).show();
            return;
        }
        String ip = ipWifi();
        if (ip == null) {
            new AlertDialog.Builder(this).setTitle("Sin WiFi")
                .setMessage("El radio no está conectado a una red WiFi. Conéctalo primero y vuelve a intentar.")
                .setPositiveButton("OK", null).show();
            return;
        }
        try {
            servidor = new ServidorWifi(new ServidorWifi.Callback() {
                public java.io.File carpetaDestino(String nombre) {
                    String n = (nombre == null) ? "" : nombre.toLowerCase(java.util.Locale.US);
                    if (n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".m4v") || n.endsWith(".mkv")
                        || n.endsWith(".webm") || n.endsWith(".avi") || n.endsWith(".mov")) {
                        return carpetaVideos();   // los videos van a la carpeta Videos
                    }
                    return carpetaDescarga();     // la música va a Descarga
                }
                public void archivoRecibido(String nombre) {
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(MainActivity.this, "Recibido: " + nombre, Toast.LENGTH_SHORT).show();
                        escanearMusica();
                    }});
                }
            });
            servidor.iniciar();
            tomarCandadosWifi();   // mantener WiFi despierto hasta que se apague a mano
            final String url = "http://" + ip + ":" + servidor.puerto;
            float dens = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout ly = new android.widget.LinearLayout(this);
            ly.setOrientation(android.widget.LinearLayout.VERTICAL);
            ly.setGravity(android.view.Gravity.CENTER);
            int pad = (int) (10 * dens); ly.setPadding(pad, pad, pad, pad);
            TextView tv1 = new TextView(this);
            tv1.setText("Escanea el QR con la cámara, o escribe la dirección de abajo:");
            tv1.setTextColor(0xFFF4F4F8); tv1.setGravity(android.view.Gravity.CENTER); tv1.setTextSize(13);
            ImageView iv = new ImageView(this);
            Bitmap qr = generarQR(url, 400);
            if (qr != null) iv.setImageBitmap(qr);
            int qs = (int) (150 * dens);   // QR más chico para que quepa la dirección
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(qs, qs);
            lp.topMargin = (int) (8 * dens); lp.bottomMargin = (int) (8 * dens); iv.setLayoutParams(lp);
            TextView tv2 = new TextView(this);
            tv2.setText(url);
            tv2.setTextColor(0xFFFFB300); tv2.setGravity(android.view.Gravity.CENTER);
            tv2.setTextSize(24); tv2.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView tv3 = new TextView(this);
            tv3.setText("(si tu celular no lee QR, escribe esa dirección en el navegador)");
            tv3.setTextColor(0xFF8B8B9A); tv3.setGravity(android.view.Gravity.CENTER); tv3.setTextSize(11);
            tv3.setPadding(0, (int)(4*dens), 0, 0);
            ly.addView(tv1); ly.addView(iv); ly.addView(tv2); ly.addView(tv3);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(ly);
            new AlertDialog.Builder(this)
                .setTitle("Recibir por WiFi — ACTIVO")
                .setView(sv)
                .setPositiveButton("Entendido", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            servidor = null;
            soltarCandadosWifi();
        }
    }
    // Genera un código QR (sin internet) con la dirección para conectarse
    private Bitmap generarQR(String texto, int size) {
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            java.util.Hashtable<com.google.zxing.EncodeHintType, Object> hints = new java.util.Hashtable<com.google.zxing.EncodeHintType, Object>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix bm = writer.encode(texto, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                int off = y * size;
                for (int x = 0; x < size; x++) px[off + x] = bm.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bmp.setPixels(px, 0, size, 0, 0, size, size);
            return bmp;
        } catch (Throwable e) { return null; }
    }
    // IP del radio en la red WiFi (IPv4 no local)
    private String ipWifi() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip != null && !ip.startsWith("127")) return ip;
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private void pedirFoco() {
        // Si hay un video abierto, cerrarlo para que no suenen los dos a la vez
        try { View pv = findViewById(R.id.paneVideo); if (pv != null && pv.getVisibility() == View.VISIBLE) cerrarVideo(); } catch (Exception e) {}
        try { am.requestAudioFocus(focoListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Exception e) {}
        try { if (mbCn != null) am.registerMediaButtonEventReceiver(mbCn); } catch (Exception e) {}
    }
    public void manejarTeclaMedia(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                siguiente(false); break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                anterior(); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                toggle(); break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                try { if (mp != null && prepared && mp.isPlaying()) { mp.pause(); pintarPlay(false); } } catch (Exception e) {}
                break;
        }
    }
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                manejarTeclaMedia(keyCode); return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private boolean shuffle = false;
    private int repeat = 0;
    private boolean prepared = false;
    private boolean noAutoStart = false;
    private boolean yaRestaurado = false;   // para restaurar la última canción solo una vez al abrir
    private int posGuardadaMs = 0;          // posición donde iba la última canción
    private int animDir = 0;   // 1 siguiente, -1 anterior, 0 sin animación

    private ImageView imgArt;
    private ImageView imgArtBg;
    private View artScrim;
    private TextView txtTitle, txtArtist, txtCur, txtDur, txtCount;
    private TextView txtNombreGrande;
    private SeekBar seek;
    private ImageButton btnPlay, btnPrev, btnNext;
    private Button btnShuffle, btnRepeat, btnEq;
    private ListView list;
    private SongAdapter adapter;
    private View paneReproduciendo, paneLista;
    private Button btnAbrirLista, btnVolver;
    // Ajustes
    private View paneAjustes, paneExplorar;
    private boolean optAuto = false, optEmbed = true, optAutoDetectar = true;
    private String carpetaVinculada = null;
    // Cachés con LÍMITE: guardan solo las últimas y botan las viejas (evita que el radio se quede sin memoria)
    private final java.util.LinkedHashMap<String, byte[]> artCache =
        new java.util.LinkedHashMap<String, byte[]>(16, 0.75f, false) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> e) { return size() > 40; }
        };
    private final java.util.LinkedHashMap<String, android.graphics.Bitmap> portadas =
        new java.util.LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, false) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> e) { return size() > 150; }
        };
    // Carátulas candidatas por canción (para poder cambiar a otra distinta con cada búsqueda)
    private final java.util.LinkedHashMap<String, java.util.ArrayList<String>> candidatosArt =
        new java.util.LinkedHashMap<String, java.util.ArrayList<String>>(16, 0.75f, false) {
            protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.ArrayList<String>> e) { return size() > 30; }
        };
    private final java.util.HashMap<String, Integer> indiceArt = new java.util.HashMap<String, Integer>();
    private ListView listExplorar;
    private android.widget.ArrayAdapter<String> expAdapter;
    private final ArrayList<String> expItems = new ArrayList<String>();
    private final ArrayList<File> expDirs = new ArrayList<File>();
    private File expActual = null;
    // Listas de reproducción
    private org.json.JSONObject listas = new org.json.JSONObject();  // nombre -> [rutas]
    private int tab = 0;   // 0 = carpetas, 1 = mis listas
    private boolean enBusqueda = false;
    private String rutaActualCache = null;   // caché de la canción que suena (para pintar rápido la lista)
    private Carpeta carpetaBusqueda;
    private final ArrayList<Song> videos = new ArrayList<Song>();
    private final java.util.HashSet<String> vistosVideo = new java.util.HashSet<String>();
    private final ArrayList<String> nombresListas = new ArrayList<String>();
    // Opciones extra
    private String optCalidad = "alta", optTema = "ambar", optOrden = "nombre";
    private boolean optResume = true, optAutoplay = false, optPausaUsb = false, optPantalla = false, optVolArranque = false;
    private static boolean volYaAplicado = false;  // estático: sobrevive a recrear la app (cámara), se reinicia al matar el proceso (arranque real)
    private int accent = 0xFFFFB020;
    private android.os.PowerManager.WakeLock wakeCpu = null;
    private android.content.BroadcastReceiver usbReceiver = null;
    private android.content.BroadcastReceiver netReceiver = null;
    private android.database.ContentObserver volObserver = null;
    private boolean descargaEnCurso = false;
    private VisualizerView vizBg = null;
    private EqNombreView eqNombre = null;
    private ParticlesView particles = null;
    private boolean efectosOn = true;
    private int efectoModo = 4;   // 0 anillos, 1 partículas, 2 brillo, 3 todos, 4 ninguno (POR DEFECTO: apagado)
    private Visualizer visualizer = null;

    private final Handler handler = new Handler();
    private SharedPreferences prefs;

    private short[] eqLevels = null;
    private boolean eqEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instalarConscrypt();  // TLS moderno dentro de la app (para el radio viejo)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("sonido", MODE_PRIVATE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        activo = this;
        mbCn = new ComponentName(getPackageName(), MediaButtonReceiver.class.getName());
        try { am.registerMediaButtonEventReceiver(mbCn); } catch (Exception e) {}

        imgArt = (ImageView) findViewById(R.id.art);
        imgArtBg = (ImageView) findViewById(R.id.artBg);
        artScrim = findViewById(R.id.artScrim);
        txtNombreGrande = (TextView) findViewById(R.id.txtNombreGrande);
        txtTitle = (TextView) findViewById(R.id.title);
        txtArtist = (TextView) findViewById(R.id.artist);
        txtCur = (TextView) findViewById(R.id.tCur);
        txtDur = (TextView) findViewById(R.id.tDur);
        txtCount = (TextView) findViewById(R.id.count);
        seek = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btnPlay);
        btnPrev = (ImageButton) findViewById(R.id.btnPrev);
        btnNext = (ImageButton) findViewById(R.id.btnNext);
        btnShuffle = (Button) findViewById(R.id.btnShuffle);
        btnRepeat = (Button) findViewById(R.id.btnRepeat);
        btnEq = (Button) findViewById(R.id.btnEq);
        list = (ListView) findViewById(R.id.list);
        paneReproduciendo = findViewById(R.id.paneReproduciendo);
        paneLista = findViewById(R.id.paneLista);
        btnAbrirLista = (Button) findViewById(R.id.btnAbrirLista);
        btnVolver = (Button) findViewById(R.id.btnVolver);

        btnAbrirLista.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarLista(true); }});
        btnVolver.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ atrasEnLista(); }});
        Button btnReproCarpeta = (Button) findViewById(R.id.btnReproCarpeta);
        Button btnAleatCarpeta = (Button) findViewById(R.id.btnAleatCarpeta);
        btnReproCarpeta.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ reproducirCarpeta(false); }});
        btnAleatCarpeta.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ reproducirCarpeta(true); }});

        // ---- Ajustes ----
        paneAjustes = findViewById(R.id.paneAjustes);
        paneExplorar = findViewById(R.id.paneExplorar);
        listExplorar = (ListView) findViewById(R.id.listExplorar);
        optAuto = prefs.getBoolean("optAuto", true);
        optEmbed = prefs.getBoolean("optEmbed", true);
        optAutoDetectar = prefs.getBoolean("autoDetectar", true);
        carpetaVinculada = prefs.getString("carpetaVinc", null);
        optCalidad = prefs.getString("calidad", "alta");
        optTema = prefs.getString("tema", "ambar");
        optOrden = prefs.getString("orden", "nombre");
        optResume = prefs.getBoolean("resume", true);
        optAutoplay = prefs.getBoolean("autoplay2", false);
        optPausaUsb = prefs.getBoolean("pausaUsb", false);
        optPantalla = prefs.getBoolean("pantalla", false);
        optVolArranque = prefs.getBoolean("volArranque2", false);
        efectosOn = prefs.getBoolean("efectos", true);
        efectoModo = prefs.getInt("efectoModo2", 4);
        aplicarTema();

        findViewById(R.id.btnAjustes).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarPane(2); }});
        findViewById(R.id.btnCerrarAjustes).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarPane(0); }});
        final CheckBox chkAuto = (CheckBox) findViewById(R.id.chkAuto);
        final CheckBox chkEmbed = (CheckBox) findViewById(R.id.chkEmbed);
        chkAuto.setChecked(optAuto); chkEmbed.setChecked(optEmbed);
        chkAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optAuto=c; prefs.edit().putBoolean("optAuto",c).apply(); }});
        chkEmbed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optEmbed=c; prefs.edit().putBoolean("optEmbed",c).apply(); }});
        findViewById(R.id.btnVincular).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ abrirExplorador(); }});
        findViewById(R.id.btnDetectarUsb).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            detectarYEnfocarUsb();
        }});
        findViewById(R.id.btnDesvincular).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ carpetaVinculada=null; prefs.edit().remove("carpetaVinc").apply(); pintarAjustes(); escanearMusica(); }});
        findViewById(R.id.btnDescargarArt).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ descargarFaltantes(); }});
        findViewById(R.id.btnWifi).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ alternarServidorWifi(); }});
        findViewById(R.id.btnYouTube).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ abrirYouTube(); }});
        findViewById(R.id.btnCancelarExplorar).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarPane(2); }});
        findViewById(R.id.btnUsarCarpeta).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ usarCarpeta(); }});

        expAdapter = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, expItems);
        listExplorar.setAdapter(expAdapter);
        listExplorar.setOnItemClickListener(new AdapterView.OnItemClickListener(){ public void onItemClick(AdapterView<?> p, View v, int position, long idd){ navegarExplorador(position); }});

        // ---- Listas de reproducción ----
        try { listas = new org.json.JSONObject(prefs.getString("listas", "{}")); } catch (Exception e) { listas = new org.json.JSONObject(); }
        if (!listas.has("Favoritas")) { try { listas.put("Favoritas", new org.json.JSONArray()); } catch (Exception e) {} }
        findViewById(R.id.tabCarpetas).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ setTab(0); }});
        findViewById(R.id.tabListas).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ setTab(1); }});
        findViewById(R.id.tabVideos).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ setTab(2); }});
        findViewById(R.id.btnCerrarVideo).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ cerrarVideo(); }});
        findViewById(R.id.btnFav).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toggleFav(); }});
        findViewById(R.id.btnMasLista).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarMasLista(); }});
        findViewById(R.id.btnBorrarActual).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ borrarCancionActual(); }});
        final GestureDetector gestos = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            public boolean onDown(MotionEvent e) { return true; }
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 90) {
                    if (dx > 0) anterior(); else siguiente(false);
                    return true;
                }
                return false;
            }
        });
        imgArt.setOnTouchListener(new View.OnTouchListener() {
            long ultimoTap = 0; int toques = 0; Runnable longP; float dx, dy;
            public boolean onTouch(View v, MotionEvent e) {
                gestos.onTouchEvent(e);
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getX(); dy = e.getY();
                        longP = new Runnable(){ public void run(){ toques = 0; cambiarCaratula(); } };
                        handler.postDelayed(longP, 700);  // mantener presionado = editar nombre
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (longP != null && (Math.abs(e.getX() - dx) > 30 || Math.abs(e.getY() - dy) > 30)) { handler.removeCallbacks(longP); longP = null; }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (longP != null) { handler.removeCallbacks(longP); longP = null; }
                        long ahora = System.currentTimeMillis();
                        if (ahora - ultimoTap < 700) toques++; else toques = 1;
                        ultimoTap = ahora;
                        if (toques >= 4) { toques = 0; buscarCaratulaDirecto(); }  // 4 toques = buscar directo (sin teclado)
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        if (longP != null) { handler.removeCallbacks(longP); longP = null; }
                        break;
                }
                return true;
            }
        });

        // ---- Efectos (partículas + anillos) ----
        vizBg = (VisualizerView) findViewById(R.id.vizBg);
        if (vizBg != null) { vizBg.setTipo(1); vizBg.setColor(accent); }
        eqNombre = (EqNombreView) findViewById(R.id.eqNombre);
        if (eqNombre != null) eqNombre.setColor(0xFFFFC107);
        particles = (ParticlesView) findViewById(R.id.particulas);
        if (particles != null) particles.setColor(accent);
        findViewById(R.id.btnVis).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            efectoModo = (efectoModo + 1) % 6; prefs.edit().putInt("efectoModo2", efectoModo).apply();
            aplicarEfectos();
            String[] nom = { "Solo anillos", "Solo partículas", "Solo brillo", "Todos", "Sin efectos", "Nombre + ecualizador" };
            Toast.makeText(MainActivity.this, nom[efectoModo], Toast.LENGTH_SHORT).show();
        }});
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener(){
            public boolean onItemLongClick(AdapterView<?> p, View v, int position, long id){
                if (modo == 1 && carpetaAbierta != null && carpetaAbierta.esLista) { quitarDeListaActual(position); return true; }
                if (modo == 1 && carpetaAbierta != null && !carpetaAbierta.esLista) { opcionesCancion(position); return true; }
                if (modo == 0 && tab == 1) { opcionesLista(position); return true; }
                if (modo == 0 && tab == 2) { opcionesVideo(position); return true; }
                return false;
            }
        });

        // ---- Ajustes extra ----
        final CheckBox chkResume = (CheckBox) findViewById(R.id.chkResume);
        final CheckBox chkAutoplay = (CheckBox) findViewById(R.id.chkAutoplay);
        final CheckBox chkPausaUsb = (CheckBox) findViewById(R.id.chkPausaUsb);
        final CheckBox chkPantalla = (CheckBox) findViewById(R.id.chkPantalla);
        final CheckBox chkVolArranque = (CheckBox) findViewById(R.id.chkVolArranque);
        final CheckBox chkAutoDetectar = (CheckBox) findViewById(R.id.chkAutoDetectar);
        chkResume.setChecked(optResume); chkAutoplay.setChecked(optAutoplay); chkPausaUsb.setChecked(optPausaUsb); chkPantalla.setChecked(optPantalla);
        chkVolArranque.setChecked(optVolArranque);
        chkAutoDetectar.setChecked(optAutoDetectar);
        chkAutoDetectar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optAutoDetectar=c; prefs.edit().putBoolean("autoDetectar",c).apply(); }});
        chkVolArranque.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optVolArranque=c; prefs.edit().putBoolean("volArranque2",c).apply(); }});
        chkResume.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optResume=c; prefs.edit().putBoolean("resume",c).apply(); }});
        chkAutoplay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optAutoplay=c; prefs.edit().putBoolean("autoplay2",c).apply(); }});
        chkPausaUsb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optPausaUsb=c; prefs.edit().putBoolean("pausaUsb",c).apply(); }});
        chkPantalla.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optPantalla=c; prefs.edit().putBoolean("pantalla",c).apply(); aplicarPantalla(); }});

        View.OnClickListener calCl = new View.OnClickListener(){ public void onClick(View v){
            if(v.getId()==R.id.calNormal) optCalidad="normal"; else if(v.getId()==R.id.calMaxima) optCalidad="maxima"; else optCalidad="alta";
            prefs.edit().putString("calidad",optCalidad).apply(); pintarSegCalidad(); }};
        findViewById(R.id.calNormal).setOnClickListener(calCl); findViewById(R.id.calAlta).setOnClickListener(calCl); findViewById(R.id.calMaxima).setOnClickListener(calCl);

        View.OnClickListener ordCl = new View.OnClickListener(){ public void onClick(View v){
            if(v.getId()==R.id.ordArtista) optOrden="artista"; else if(v.getId()==R.id.ordFecha) optOrden="fecha"; else optOrden="nombre";
            prefs.edit().putString("orden",optOrden).apply(); pintarSegOrden(); reordenar(); }};
        findViewById(R.id.ordNombre).setOnClickListener(ordCl); findViewById(R.id.ordArtista).setOnClickListener(ordCl); findViewById(R.id.ordFecha).setOnClickListener(ordCl);

        View.OnClickListener temaCl = new View.OnClickListener(){ public void onClick(View v){
            if(v.getId()==R.id.temaAzul) optTema="azul"; else if(v.getId()==R.id.temaRojo) optTema="rojo"; else if(v.getId()==R.id.temaVerde) optTema="verde"; else optTema="ambar";
            prefs.edit().putString("tema",optTema).apply(); aplicarTema(); }};
        findViewById(R.id.temaAmbar).setOnClickListener(temaCl); findViewById(R.id.temaAzul).setOnClickListener(temaCl); findViewById(R.id.temaRojo).setOnClickListener(temaCl); findViewById(R.id.temaVerde).setOnClickListener(temaCl);

        pintarSegCalidad(); pintarSegOrden();
        aplicarPantalla();
        registrarUsbReceiver();
        registrarNetReceiver();

        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", 0);
        eqEnabled = prefs.getBoolean("eqOn", true);
        pintarShuffle(); pintarRepeat();

        adapter = new SongAdapter();
        list.setAdapter(adapter);
        final EditText etBuscar = (EditText) findViewById(R.id.etBuscar);
        etBuscar.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filtrarBusqueda(s.toString()); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        findViewById(R.id.btnLimpiarBuscar).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            etBuscar.setText("");
            try { android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); imm.hideSoftInputFromWindow(etBuscar.getWindowToken(), 0); } catch (Exception e) {}
        }});
        findViewById(R.id.btnVozBuscar).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ buscarPorVoz(); }});
        // Si el radio NO tiene reconocimiento de voz (no trae la app de Google), esconder el botón
        try {
            java.util.List<android.content.pm.ResolveInfo> lv = getPackageManager().queryIntentActivities(
                new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
            if (lv == null || lv.isEmpty()) findViewById(R.id.btnVozBuscar).setVisibility(View.GONE);
        } catch (Throwable t) { try { findViewById(R.id.btnVozBuscar).setVisibility(View.GONE); } catch (Exception e) {} }
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int position, long idd) {
                if (modo == 0) { if (tab == 0) abrirCarpeta(position); else if (tab == 1) abrirLista(position); else abrirVideo(position); }
                else { reproducirDeCarpeta(position); }
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toggle(); }});
        btnNext.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ siguiente(false); }});
        btnPrev.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ anterior(); }});
        btnShuffle.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply();
            int actual = (posEnOrden>=0 && posEnOrden<order.size()) ? order.get(posEnOrden) : -1;
            construirOrden();
            if(actual>=0) posEnOrden = order.indexOf(actual);
            pintarShuffle();
        }});
        btnRepeat.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            repeat = (repeat+1)%3; prefs.edit().putInt("repeat", repeat).apply(); pintarRepeat();
        }});
        btnEq.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarEq(); }});

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean fromUser){ if(fromUser && mp!=null && prepared) txtCur.setText(fmt(p)); }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){ if(mp!=null && prepared) mp.seekTo(s.getProgress()); }
        });

        int maxv = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // Volumen al arrancar: APAGADO por defecto. Aunque se encienda, solo una vez por arranque
        // real y NUNCA si el radio está en mute (así no se cancela el silencio al volver de la cámara).
        if (optVolArranque && !volYaAplicado) {
            volYaAplicado = true;
            if (!estaEnMute()) { am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (maxv * 0.30), 0); }
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        // Ya NO pedimos prioridad de audio al abrir: eso hacía que el radio quitara el mute
        // al volver de la cámara. Solo se pide cuando el usuario le da play.

        cargarEqGuardado();
        escanearMusica();
        // Reintentos por si la memoria USB monta un segundo después de abrir la app (sobre todo la 1ª vez)
        handler.postDelayed(new Runnable(){ public void run(){ if (optAutoDetectar && totalCanciones() == 0) escanearMusica(); } }, 2500);
        handler.postDelayed(new Runnable(){ public void run(){ if (optAutoDetectar && totalCanciones() == 0) escanearMusica(); } }, 6000);

        // PRIMER ARRANQUE tras instalar: dejar todo listo (efectos apagados, auto-detección, detectar USB)
        if (prefs.getBoolean("primeraVez", true)) {
            prefs.edit().putBoolean("primeraVez", false)
                .putInt("efectoModo2", 4)        // efectos de Vista: apagados
                .putBoolean("autoDetectar", true) // detectar USB solo
                .apply();
            efectoModo = 4; optAutoDetectar = true;
            Toast.makeText(this, "Bienvenido. Detectando música del USB…", Toast.LENGTH_LONG).show();
            handler.postDelayed(new Runnable(){ public void run(){ escanearMusica(); } }, 1200);
            handler.postDelayed(new Runnable(){ public void run(){ escanearMusica(); } }, 3500);
            handler.postDelayed(new Runnable(){ public void run(){ escanearMusica(); } }, 8000);
        }
    }

    private int totalCanciones() {
        int n = 0;
        try { for (Carpeta c : carpetas) { if (!c.esLista && c.songs != null) n += c.songs.size(); } } catch (Exception e) {}
        return n;
    }

    private static final int MAX_DEPTH = 9;

    private volatile boolean escaneando = false;
    private volatile boolean rescanPendiente = false;
    private void escanearMusica() {
        if (escaneando) { rescanPendiente = true; return; }  // ya hay uno; no duplicar
        escaneando = true;
        txtCount.setText("Buscando música…");
        new Thread(new Runnable() {
            public void run() {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                try {
                    final ArrayList<Song> found = new ArrayList<Song>();
                    Set<String> vistos = new HashSet<String>();
                    videos.clear(); vistosVideo.clear();
                    for (File root : raices()) {
                        buscarAudio(root, found, vistos, 0);
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            agruparEnCarpetas(found);
                            modo = 0; carpetaAbierta = null;
                            adapter.notifyDataSetChanged();
                            actualizarHeaderLista();
                            calcularPortadasCarpetas();
                            if (optAuto && hayInternet() && !carpetas.isEmpty()) descargarFaltantes();
                            restaurarUltima();
                        }
                    });
                } finally {
                    escaneando = false;
                    if (rescanPendiente) { rescanPendiente = false; handler.postDelayed(new Runnable(){ public void run(){ escanearMusica(); } }, 500); }
                }
            }
        }).start();
    }

    // Carpeta "Descarga": donde llegan las canciones enviadas por WiFi
    // Carpeta "Videos" dentro del USB (para separar los videos de la música)
    private File carpetaVideos() {
        File d = null;
        try {
            String usb = carpetaVinculada;
            if (usb == null) usb = detectarRutaUsb();
            if (usb != null) { File f = new File(usb, "Videos"); if (!f.exists()) f.mkdirs(); if (f.exists() && f.canWrite()) d = f; }
        } catch (Exception e) {}
        if (d == null) { try { d = new File(Environment.getExternalStorageDirectory(), "Videos"); if (!d.exists()) d.mkdirs(); } catch (Exception e) {} }
        if (d == null || !d.exists()) { try { d = new File(getExternalFilesDir(null), "Videos"); if (!d.exists()) d.mkdirs(); } catch (Exception e) {} }
        return d;
    }

    private File carpetaDescarga() {
        File d = null;
        // 1) PRIMERO dentro del USB (la memoria enfocada/vinculada), para que se vea en la PC y aparezca en el radio
        try {
            String usb = carpetaVinculada;
            if (usb == null) usb = detectarRutaUsb();
            if (usb != null) { File f = new File(usb, "Descarga"); if (!f.exists()) f.mkdirs(); if (f.exists() && f.canWrite()) d = f; }
        } catch (Exception e) {}
        // 2) Si no hay USB escribible, memoria interna del radio
        if (d == null) { try { d = new File(Environment.getExternalStorageDirectory(), "Descarga"); if (!d.exists()) d.mkdirs(); } catch (Exception e) {} }
        if (d == null || !d.exists()) { try { d = new File(getExternalFilesDir(null), "Descarga"); if (!d.exists()) d.mkdirs(); } catch (Exception e) {} }
        return d;
    }

    // Carpetas donde buscar: SIEMPRE incluye "Descarga"; si hay una vinculada, esa; si no, todo
    private ArrayList<File> raices() {
        ArrayList<File> r = new ArrayList<File>();
        try { File desc = carpetaDescarga(); if (desc != null && desc.exists() && desc.canRead()) r.add(desc); } catch (Exception e) {}
        try { File vid = carpetaVideos(); if (vid != null && vid.exists() && vid.canRead()) r.add(vid); } catch (Exception e) {}
        if (carpetaVinculada != null) {
            File f = new File(carpetaVinculada);
            if (f.exists() && f.canRead()) { r.add(f); return r; }
        }
        r.addAll(raicesBase());
        return r;
    }
    // Cuenta rápida de canciones en una carpeta (poca profundidad) para saber si tiene música
    private int contarMusicaRapido(File dir, int depth) {
        if (dir == null || depth > 4) return 0;
        File[] hijos; try { hijos = dir.listFiles(); } catch (Exception e) { return 0; }
        if (hijos == null) return 0;
        int n = 0;
        for (File f : hijos) {
            try {
                if (f.isDirectory()) {
                    String nm = f.getName();
                    if (nm.startsWith(".") || nm.equalsIgnoreCase("Android")) continue;
                    n += contarMusicaRapido(f, depth + 1);
                } else {
                    String low = f.getName().toLowerCase(Locale.US);
                    if (low.endsWith(".mp3") || low.endsWith(".m4a") || low.endsWith(".aac") || low.endsWith(".flac") || low.endsWith(".wav") || low.endsWith(".ogg")) n++;
                }
                if (n > 300) return n;  // suficiente para saber que tiene música
            } catch (Exception e) {}
        }
        return n;
    }
    // Busca la MEMORIA USB (externa, no la interna) que tenga música. Devuelve su ruta o null.
    private String detectarRutaUsb() {
        String interno = "";
        try { interno = Environment.getExternalStorageDirectory().getCanonicalPath(); } catch (Exception e) { try { interno = Environment.getExternalStorageDirectory().getAbsolutePath(); } catch (Exception e2) {} }
        java.util.LinkedHashSet<String> cand = new java.util.LinkedHashSet<String>();
        try { String sec = System.getenv("SECONDARY_STORAGE"); if (sec != null) for (String p : sec.split(":")) cand.add(p); } catch (Exception e) {}
        for (String raiz : new String[]{ "/storage", "/mnt", "/mnt/media_rw" }) {
            File r = new File(raiz);
            File[] hs = null; try { hs = r.listFiles(); } catch (Exception e) {}
            if (hs != null) for (File h : hs) { try { if (h.isDirectory() && h.canRead()) cand.add(h.getAbsolutePath()); } catch (Exception e) {} }
        }
        String mejor = null; int mejorN = 0;
        for (String p : cand) {
            try {
                File f = new File(p);
                if (!f.isDirectory() || !f.canRead()) continue;
                String cp; try { cp = f.getCanonicalPath(); } catch (Exception e) { cp = f.getAbsolutePath(); }
                String low = cp.toLowerCase(Locale.US);
                if (cp.equals(interno)) continue;
                if (low.endsWith("/emulated") || low.endsWith("/self") || low.equals("/storage") || low.equals("/mnt") || low.contains("emulated/0")) continue;
                int n = contarMusicaRapido(f, 0);
                if (n > mejorN) { mejorN = n; mejor = cp; }
            } catch (Exception e) {}
        }
        return mejorN > 0 ? mejor : null;
    }
    // Detecta el USB y enfoca SOLO en él (ignora la memoria interna del radio)
    private void detectarYEnfocarUsb() {
        final TextView t = (TextView) findViewById(R.id.txtDetectUsb);
        if (t != null) t.setText("Buscando la memoria USB…");
        new Thread(new Runnable() {
            public void run() {
                final String usb = detectarRutaUsb();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (usb != null) {
                            carpetaVinculada = usb;
                            prefs.edit().putString("carpetaVinc", usb).apply();
                            if (t != null) t.setText("Enfocado en el USB. Cargando…");
                            escanearMusica();
                            handler.postDelayed(new Runnable(){ public void run(){
                                if (t != null) t.setText("USB: " + totalCanciones() + " canciones (solo memoria USB)");
                                pintarAjustes();
                            }}, 1200);
                        } else {
                            if (t != null) t.setText("No se encontró memoria USB con música. Revisa que esté conectada.");
                            escanearMusica();
                        }
                    }
                });
            }
        }).start();
    }

    private ArrayList<File> raicesBase() {
        LinkedHashSet<String> paths = new LinkedHashSet<String>();
        try { paths.add(Environment.getExternalStorageDirectory().getAbsolutePath()); } catch (Exception e) {}
        try { String sec = System.getenv("SECONDARY_STORAGE"); if (sec != null) for (String p : sec.split(":")) paths.add(p); } catch (Exception e) {}
        try { String ext = System.getenv("EXTERNAL_STORAGE"); if (ext != null) paths.add(ext); } catch (Exception e) {}
        String[] comunes = {
            "/storage", "/mnt", "/mnt/usb", "/mnt/usbhost", "/mnt/usbhost1", "/mnt/usbhost0",
            "/mnt/usb_storage", "/mnt/usb1", "/mnt/sdcard/usbStorage", "/mnt/media_rw",
            "/storage/usb", "/storage/usbdisk", "/storage/UsbDriveA", "/storage/usbotg",
            "/udisk", "/mnt/udisk", "/mnt/sda", "/mnt/sda1", "/mnt/ext_sdcard"
        };
        for (String p : comunes) paths.add(p);
        ArrayList<File> r = new ArrayList<File>();
        for (String p : paths) {
            try { File f = new File(p); if (f.exists() && f.canRead()) r.add(f); } catch (Exception e) {}
        }
        return r;
    }

    private void buscarAudio(File dir, ArrayList<Song> out, Set<String> vistos, int depth) {
        if (dir == null || depth > MAX_DEPTH || out.size() > 5000) return;
        File[] hijos;
        try { hijos = dir.listFiles(); } catch (Exception e) { return; }
        if (hijos == null) return;
        for (File f : hijos) {
            try {
                if (f.isDirectory()) {
                    String nm = f.getName();
                    if (nm.startsWith(".") || nm.equalsIgnoreCase("Android")) continue;
                    buscarAudio(f, out, vistos, depth + 1);
                } else {
                    String low = f.getName().toLowerCase(Locale.US);
                    if (low.endsWith(".mp3") || low.endsWith(".m4a") || low.endsWith(".aac")
                        || low.endsWith(".wav") || low.endsWith(".ogg") || low.endsWith(".flac")
                        || low.endsWith(".wma") || low.endsWith(".opus")) {
                        String path = f.getAbsolutePath();
                        if (vistos.contains(path)) continue;
                        vistos.add(path);
                        Song s = new Song();
                        s.path = path;
                        s.title = limpiarNombre(f.getName());
                        s.artist = "";
                        s.dur = 0;
                        s.albumId = 0;
                        try { s.fecha = f.lastModified(); } catch (Exception ex) { s.fecha = 0; }
                        out.add(s);
                    } else if (low.endsWith(".mp4") || low.endsWith(".3gp") || low.endsWith(".m4v")
                        || low.endsWith(".mkv") || low.endsWith(".webm") || low.endsWith(".avi") || low.endsWith(".mov")) {
                        String path = f.getAbsolutePath();
                        if (vistosVideo.contains(path)) continue;
                        vistosVideo.add(path);
                        Song v = new Song();
                        v.path = path;
                        v.title = limpiarNombre(f.getName());
                        try { v.fecha = f.lastModified(); } catch (Exception ex) { v.fecha = 0; }
                        videos.add(v);
                    }
                }
            } catch (Exception e) {}
        }
    }

    private String limpiarNombre(String n) {
        if (n == null) return "(sin nombre)";
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace('_', ' ').trim();
    }

    // Nombre a mostrar de una canción: usa el título, y si no hay, saca el nombre del archivo desde el path.
    private String nombreDe(Song s) {
        if (s == null) return "";
        if (s.title != null && s.title.trim().length() > 0) return s.title;
        if (s.path != null) { try { return limpiarNombre(new File(s.path).getName()); } catch (Exception e) {} }
        return "";
    }

    // Lee título/artista/carátula reales del archivo que suena (trabajo pesado en 2º plano = no traba)
    private void cargarMetadatosActual(final Song s) {
        if (s == null) return;
        rutaActualCache = s.path;
        if (eqNombre != null) eqNombre.setInfo(s.artist != null && s.artist.length() > 0 ? s.artist : "", s.title);
        final int animDirCap = animDir; animDir = 0;
        // RÁPIDO (hilo principal): mostrar lo que ya sabemos y animar
        txtTitle.setText(s.title);
        txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
        animarEntradaCaratula(animDirCap);
        final String pathCap = s.path;
        // PESADO (2º plano): leer metadatos del archivo + decodificar carátula + desenfoque
        new Thread(new Runnable() {
            public void run() {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                final String[] m = new String[3];
                Bitmap bmp = null;
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(pathCap);
                    m[0] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    m[1] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    m[2] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    byte[] pic = r.getEmbeddedPicture();
                    try { r.release(); } catch (Exception e) {}
                    if (pic != null) bmp = decodeEscalado(pic, 512);
                } catch (Exception e) {}
                if (bmp == null) {
                    byte[] c = null;
                    synchronized (artCache) { if (artCache.containsKey(pathCap)) c = artCache.get(pathCap); }
                    if (c != null) { try { bmp = decodeEscalado(c, 512); } catch (Exception e) {} }
                }
                final Bitmap portada = bmp;
                final Bitmap fondo = (bmp != null) ? desenfocar(bmp, 60, 7) : null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        // Si el usuario ya cambió de canción, no aplicar (evita parpadeo y pintar la carátula equivocada)
                        if (pathCap == null || !pathCap.equals(rutaActualCache)) return;
                        if (m[0] != null && m[0].trim().length() > 0) { s.title = m[0]; txtTitle.setText(m[0]); }
                        if (m[1] != null && m[1].trim().length() > 0) { s.artist = m[1]; txtArtist.setText(m[1]); }
                        if (m[2] != null && m[2].trim().length() > 0) s.album = m[2];
                        if (eqNombre != null) eqNombre.setInfo(s.artist != null && s.artist.length() > 0 ? s.artist : "", s.title);
                        if (portada != null) {
                            imgArt.setImageBitmap(portada);
                            if (txtNombreGrande != null) txtNombreGrande.setVisibility(View.GONE);
                            try {
                                Bitmap fbg = (fondo != null) ? fondo : Bitmap.createScaledBitmap(portada, 20, 20, true);
                                imgArtBg.setImageBitmap(fbg);
                                imgArtBg.setBackgroundColor(0xFF06060A);
                                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(); cm.setSaturation(1.5f);
                                android.graphics.ColorMatrix osc = new android.graphics.ColorMatrix(); osc.setScale(0.72f, 0.72f, 0.72f, 1f);
                                cm.postConcat(osc);
                                imgArtBg.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                            } catch (Exception e) { imgArtBg.setImageDrawable(null); imgArtBg.setBackgroundColor(0xFF06060A); }
                            artScrim.setVisibility(View.VISIBLE);
                        } else {
                            imgArt.setImageDrawable(new ColorDrawable(0x00000000));
                            imgArtBg.setImageDrawable(null); imgArtBg.setColorFilter(null); imgArtBg.setBackgroundColor(0xFF000000);
                            artScrim.setVisibility(View.GONE);
                            if (txtNombreGrande != null) { txtNombreGrande.setText(nombreDe(s)); txtNombreGrande.setVisibility(View.VISIBLE); }
                        }
                        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                    }
                });
            }
        }).start();
    }
    // Animación de entrada de la carátula (deslizar + giro 3D)
    private void animarEntradaCaratula(int animDirLocal) {
        if (animDirLocal == 0) return;
        try {
            int w = imgArt.getWidth(); if (w <= 0) w = 400;
            android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
            android.view.animation.TranslateAnimation ta = new android.view.animation.TranslateAnimation(animDirLocal * w * 0.6f, 0, 0, 0);
            android.view.animation.ScaleAnimation sa = new android.view.animation.ScaleAnimation(
                0.3f, 1f, 0.85f, 1f,
                android.view.animation.Animation.RELATIVE_TO_SELF, animDirLocal == 1 ? 0f : 1f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            android.view.animation.AlphaAnimation aa = new android.view.animation.AlphaAnimation(0.2f, 1f);
            set.addAnimation(ta); set.addAnimation(sa); set.addAnimation(aa);
            set.setDuration(340);
            set.setInterpolator(new android.view.animation.DecelerateInterpolator());
            imgArt.startAnimation(set);
            if (imgArtBg != null) { android.view.animation.AlphaAnimation ab = new android.view.animation.AlphaAnimation(0.4f, 1f); ab.setDuration(340); imgArtBg.startAnimation(ab); }
        } catch (Exception e) {}
    }

    private void construirOrden() {
        order.clear();
        for (int i = 0; i < songs.size(); i++) order.add(i);
        if (shuffle) Collections.shuffle(order);
    }

    // Agrupa las canciones encontradas por carpeta
    private void agruparEnCarpetas(ArrayList<Song> found) {
        carpetas.clear();
        LinkedHashMap<String, Carpeta> map = new LinkedHashMap<String, Carpeta>();
        for (Song s : found) {
            String dir;
            try { dir = new File(s.path).getParent(); } catch (Exception e) { dir = "/"; }
            if (dir == null) dir = "/";
            Carpeta c = map.get(dir);
            if (c == null) {
                c = new Carpeta();
                c.path = dir;
                String nm = new File(dir).getName();
                c.name = (nm == null || nm.length() == 0) ? dir : nm;
                map.put(dir, c);
            }
            c.songs.add(s);
        }
        carpetas.addAll(map.values());
        for (Carpeta c : carpetas) ordenarSongs(c.songs);
        Collections.sort(carpetas, new Comparator<Carpeta>() {
            public int compare(Carpeta a, Carpeta b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    private void actualizarHeaderLista() {
        Button br = (Button) findViewById(R.id.btnReproCarpeta);
        Button ba = (Button) findViewById(R.id.btnAleatCarpeta);
        View tabsRow = findViewById(R.id.tabsRow);
        if (modo == 0) {
            btnVolver.setText("< Reproduciendo");
            if (tab == 0) txtCount.setText(carpetas.isEmpty() ? "No se encontró música (revisa el USB)" : (carpetas.size() + " carpetas"));
            else txtCount.setText(nombresListas.size() + " listas");
            br.setVisibility(View.GONE); ba.setVisibility(View.GONE);
            tabsRow.setVisibility(View.VISIBLE);
        } else {
            btnVolver.setText(carpetaAbierta != null && carpetaAbierta.esLista ? "< Listas" : "< Carpetas");
            txtCount.setText((carpetaAbierta != null ? carpetaAbierta.name : "") + " · " + cancionesCarpeta.size() + " canciones");
            br.setVisibility(View.VISIBLE); ba.setVisibility(View.VISIBLE);
            tabsRow.setVisibility(View.GONE);
        }
    }

    private void setTab(int t) {
        tab = t; modo = 0; carpetaAbierta = null;
        ((Button) findViewById(R.id.tabCarpetas)).setTextColor(t == 0 ? accent : 0xFF8B8B9A);
        findViewById(R.id.tabCarpetas).setBackgroundColor(t == 0 ? 0xFF262633 : 0xFF1C1C26);
        ((Button) findViewById(R.id.tabListas)).setTextColor(t == 1 ? accent : 0xFF8B8B9A);
        findViewById(R.id.tabListas).setBackgroundColor(t == 1 ? 0xFF262633 : 0xFF1C1C26);
        Button tv = (Button) findViewById(R.id.tabVideos);
        if (tv != null) { tv.setTextColor(t == 2 ? accent : 0xFF8B8B9A); tv.setBackgroundColor(t == 2 ? 0xFF262633 : 0xFF1C1C26); }
        if (t == 0) { calcularPortadasCarpetas(); }
        if (t == 1) { construirNombresListas(); calcularPortadasListas(); }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        actualizarHeaderLista();
    }

    private void construirNombresListas() {
        nombresListas.clear();
        nombresListas.add("Favoritas");
        // Primero, el orden que el usuario haya guardado
        try {
            String s = prefs.getString("ordenListas", "");
            if (s.length() > 0) {
                org.json.JSONArray a = new org.json.JSONArray(s);
                for (int i = 0; i < a.length(); i++) { String k = a.optString(i); if (!k.equals("Favoritas") && listas.has(k) && !nombresListas.contains(k)) nombresListas.add(k); }
            }
        } catch (Exception e) {}
        // Luego, cualquier lista nueva que aún no esté en el orden
        java.util.Iterator<String> it = listas.keys();
        while (it.hasNext()) { String k = it.next(); if (!k.equals("Favoritas") && !nombresListas.contains(k)) nombresListas.add(k); }
    }
    private Song songPorRuta(String path) {
        for (Carpeta c : carpetas) for (Song s : c.songs) if (s.path.equals(path)) return s;
        return null;
    }
    // Restaura la última canción que sonaba (y su posición), lista para dar Play. Solo una vez al abrir.
    private void restaurarUltima() {
        if (yaRestaurado || !optResume) return;
        String lp = prefs.getString("lastPath", null);
        if (lp == null) return;
        Song s = songPorRuta(lp);
        if (s == null) return;   // el USB aún no está listo; se reintenta en el próximo escaneo
        for (Carpeta c : carpetas) {
            int ix = c.songs.indexOf(s);
            if (ix >= 0) {
                songs.clear(); songs.addAll(c.songs); construirOrden();
                posEnOrden = order.indexOf(ix); if (posEnOrden < 0) posEnOrden = 0;
                noAutoStart = !optAutoplay;              // cargar en pausa (lista para Play), salvo que auto-reproducir esté activo
                posGuardadaMs = prefs.getInt("lastPos", 0);
                yaRestaurado = true;
                cargarYReproducir();
                break;
            }
        }
    }
    private void abrirLista(int pos) {
        if (pos < 0 || pos >= nombresListas.size()) return;
        String n = nombresListas.get(pos);
        ArrayList<Song> arr = new ArrayList<Song>();
        try { org.json.JSONArray a = listas.getJSONArray(n); for (int k = 0; k < a.length(); k++) { Song s = songPorRuta(a.optString(k)); if (s != null) arr.add(s); } } catch (Exception e) {}
        carpetaAbierta = new Carpeta(); carpetaAbierta.name = n; carpetaAbierta.esLista = true; carpetaAbierta.songs = arr;
        cancionesCarpeta = arr; modo = 1;
        adapter.notifyDataSetChanged(); list.setSelection(0); actualizarHeaderLista();
    }

    private Song cancionActual() {
        if (posEnOrden >= 0 && posEnOrden < order.size() && order.get(posEnOrden) < songs.size()) return songs.get(order.get(posEnOrden));
        return null;
    }
    private int indiceEnArray(org.json.JSONArray a, String v) { for (int i = 0; i < a.length(); i++) { if (v.equals(a.optString(i))) return i; } return -1; }
    private org.json.JSONArray sinIndice(org.json.JSONArray a, int idx) { org.json.JSONArray n = new org.json.JSONArray(); for (int i = 0; i < a.length(); i++) { if (i != idx) n.put(a.optString(i)); } return n; }
    private void guardarListas() { prefs.edit().putString("listas", listas.toString()).apply(); }
    private boolean enAlgunaLista(String path) {
        java.util.Iterator<String> it = listas.keys();
        while (it.hasNext()) { try { if (indiceEnArray(listas.getJSONArray(it.next()), path) >= 0) return true; } catch (Exception e) {} }
        return false;
    }
    private void pintarFav() {
        Button b = (Button) findViewById(R.id.btnFav); Song s = cancionActual(); boolean fav = false;
        if (s != null) fav = enAlgunaLista(s.path);
        b.setTextColor(fav ? accent : 0xFFFFFFFF);
    }
    private void toggleFav() {
        Song s = cancionActual(); if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        try { org.json.JSONArray f = listas.getJSONArray("Favoritas"); int idx = indiceEnArray(f, s.path);
            if (idx >= 0) listas.put("Favoritas", sinIndice(f, idx)); else f.put(s.path);
            guardarListas(); pintarFav(); } catch (Exception e) {}
    }
    private void agregarALista(final String n, final Song s) {
        try {
            if (!listas.has(n)) listas.put(n, new org.json.JSONArray());
            final org.json.JSONArray a = listas.getJSONArray(n);
            if (indiceEnArray(a, s.path) >= 0) {
                new AlertDialog.Builder(this).setMessage("Ya está en \"" + n + "\". ¿Agregar otra vez?")
                    .setPositiveButton("Sí", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { a.put(s.path); guardarListas(); Toast.makeText(MainActivity.this, "Agregada a " + n, Toast.LENGTH_SHORT).show(); pintarFav(); }
                    }).setNegativeButton("No", null).show();
                return;
            }
            a.put(s.path); guardarListas();
            Toast.makeText(this, "Agregada a " + n, Toast.LENGTH_SHORT).show(); pintarFav();
        } catch (Exception e) {}
    }
    private void mostrarMasLista() {
        final Song s = cancionActual(); if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        construirNombresListas();
        final ArrayList<String> opts = new ArrayList<String>(nombresListas); opts.add("+ Crear nueva lista");
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("Agregar a lista");
        b.setItems(opts.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                if (w == opts.size() - 1) crearListaDialog(s); else agregarALista(opts.get(w), s);
            }
        });
        b.show();
    }
    private void crearListaDialog(final Song s) {
        final EditText et = new EditText(this); et.setHint("Nombre de la lista");
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("Nueva lista"); b.setView(et);
        b.setPositiveButton("Crear", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { String n = et.getText().toString().trim(); if (n.length() > 0) agregarALista(n, s); }
        });
        b.setNegativeButton("Cancelar", null); b.show();
    }
    private void quitarDeListaActual(final int p) {
        if (p < 0 || p >= cancionesCarpeta.size()) return;
        final Song s = cancionesCarpeta.get(p);
        new AlertDialog.Builder(this).setMessage("¿Quitar de la lista \"" + carpetaAbierta.name + "\"?")
            .setPositiveButton("Quitar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try { org.json.JSONArray a = listas.getJSONArray(carpetaAbierta.name); int idx = indiceEnArray(a, s.path); if (idx >= 0) { listas.put(carpetaAbierta.name, sinIndice(a, idx)); guardarListas(); } } catch (Exception e) {}
                    cancionesCarpeta.remove(p); adapter.notifyDataSetChanged(); actualizarHeaderLista();
                }
            }).setNegativeButton("Cancelar", null).show();
    }

    // Eliminar una canción del USB (borrado real del archivo)
    private void borrarCancion(final int p) {
        if (p < 0 || p >= cancionesCarpeta.size()) return;
        final Song s = cancionesCarpeta.get(p);
        String nom = nombreDe(s);
        new AlertDialog.Builder(this)
            .setTitle("Eliminar canción")
            .setMessage("¿Borrar este archivo del USB?\n\n" + nom + "\n\nEsto no se puede deshacer.")
            .setPositiveButton("Eliminar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    Song sonando = cancionActual();
                    boolean eraLaQueSuena = (sonando != null && sonando.path != null && sonando.path.equals(s.path));
                    if (eraLaQueSuena) {
                        try { if (mp != null) { mp.stop(); mp.reset(); } } catch (Exception e) {}
                        prepared = false; pintarPlay(false);
                    }
                    boolean borrado = false;
                    try { borrado = new File(s.path).delete(); } catch (Exception e) {}
                    if (borrado) {
                        cancionesCarpeta.remove(p);
                        for (int i = songs.size() - 1; i >= 0; i--) { if (songs.get(i).path != null && songs.get(i).path.equals(s.path)) songs.remove(i); }
                        construirOrden();
                        if (!eraLaQueSuena && sonando != null) { int ix = songs.indexOf(sonando); posEnOrden = (ix >= 0) ? order.indexOf(ix) : posEnOrden; }
                        adapter.notifyDataSetChanged();
                        actualizarHeaderLista();
                        Toast.makeText(MainActivity.this, "Canción eliminada del USB", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "No se pudo borrar (¿USB de solo lectura?)", Toast.LENGTH_LONG).show();
                    }
                }
            }).setNegativeButton("Cancelar", null).show();
    }

    private void abrirCarpeta(int idx) {
        if (idx < 0 || idx >= carpetas.size()) return;
        carpetaAbierta = carpetas.get(idx);
        cancionesCarpeta = carpetaAbierta.songs;
        modo = 1;
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        actualizarHeaderLista();
    }

    // Reproduce toda la carpeta abierta (en orden o aleatorio)
    private void reproducirCarpeta(boolean aleatorio) {
        if (cancionesCarpeta.isEmpty()) return;
        songs.clear();
        songs.addAll(cancionesCarpeta);
        shuffle = aleatorio;
        prefs.edit().putBoolean("shuffle", shuffle).apply();
        pintarShuffle();
        construirOrden();
        posEnOrden = 0;
        cargarYReproducir();
        mostrarLista(false);
    }

    // Reproduce una canción tocada dentro de la carpeta abierta
    private void reproducirDeCarpeta(int idx) {
        if (idx < 0 || idx >= cancionesCarpeta.size()) return;
        songs.clear();
        songs.addAll(cancionesCarpeta);
        construirOrden();
        reproducirCancion(idx);
        mostrarLista(false);
    }

    private void atrasEnLista() {
        if (modo == 1) { modo = 0; adapter.notifyDataSetChanged(); actualizarHeaderLista(); }
        else { mostrarLista(false); }
    }

    private void reproducirCancion(int songIndex) {
        int op = order.indexOf(songIndex);
        if (op < 0) { construirOrden(); op = order.indexOf(songIndex); }
        posEnOrden = op;
        cargarYReproducir();
    }

    private void cargarYReproducir() {
        if (posEnOrden < 0 || posEnOrden >= order.size()) return;
        final Song s = songs.get(order.get(posEnOrden));
        prepared = false;
        try {
            if (mp != null) { mp.reset(); }
            else {
                mp = new MediaPlayer();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mp.setDataSource(s.path);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    seek.setMax(m.getDuration());
                    txtDur.setText(fmt(m.getDuration()));
                    configurarEq();
                    if (posGuardadaMs > 0) { try { m.seekTo(posGuardadaMs); seek.setProgress(posGuardadaMs); txtCur.setText(fmt(posGuardadaMs)); } catch (Exception e) {} posGuardadaMs = 0; }
                    if (noAutoStart) { noAutoStart = false; pintarPlay(false); }
                    else { pedirFoco(); m.start(); pintarPlay(true); handler.post(actualizador); }
                    attachVisualizer();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { siguiente(true); }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo reproducir: " + s.title, Toast.LENGTH_SHORT).show();
            return;
        }
        txtTitle.setText(s.title);
        txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
        cargarMetadatosActual(s);
        pintarFav();
        if (optAuto && hayInternet()) descargarArtDe(s);
        prefs.edit().putString("lastPath", s.path).apply();
        adapter.notifyDataSetChanged();
        prefs.edit().putInt("last", order.get(posEnOrden)).apply();
    }

    private void toggle() {
        if (mp == null || !prepared) {
            if (!songs.isEmpty()) { if (posEnOrden < 0) posEnOrden = 0; cargarYReproducir(); }
            return;
        }
        if (mp.isPlaying()) { mp.pause(); pintarPlay(false); }
        else { pedirFoco(); mp.start(); pintarPlay(true); handler.post(actualizador); }
    }

    private void siguiente(boolean auto) {
        if (order.isEmpty()) return;
        if (repeat == 2 && auto) { if (mp != null) { mp.seekTo(0); mp.start(); } return; }
        if (posEnOrden < order.size() - 1) posEnOrden++;
        else { if (repeat == 1 || !auto) posEnOrden = 0; else { pintarPlay(false); return; } }
        animDir = 1;
        cargarYReproducir();
    }

    private void anterior() {
        if (order.isEmpty()) return;
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        if (posEnOrden > 0) posEnOrden--; else posEnOrden = order.size() - 1;
        animDir = -1;
        cargarYReproducir();
    }

    private int tickGuardar = 0;
    private final Runnable actualizador = new Runnable() {
        public void run() {
            if (mp != null && prepared && mp.isPlaying()) {
                int cp = mp.getCurrentPosition();
                seek.setProgress(cp);
                txtCur.setText(fmt(cp));
                if (++tickGuardar >= 4) { tickGuardar = 0; try { prefs.edit().putInt("lastPos", cp).apply(); } catch (Exception e) {} }  // guardar dónde va cada ~2s
            }
            handler.postDelayed(this, 500);
        }
    };

    private void cargarEqGuardado() {
        String g = prefs.getString("eqBands", null);
        if (g != null) {
            try {
                String[] parts = g.split(",");
                eqLevels = new short[parts.length];
                for (int i = 0; i < parts.length; i++) eqLevels[i] = (short) Integer.parseInt(parts[i]);
            } catch (Exception e) { eqLevels = null; }
        }
    }

    private void aplicarPresetLatino(String nombre) {
        if (eq == null) return;
        try {
            short bands = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            short min = range[0], max = range[1];
            for (short b = 0; b < bands; b++) {
                int f = eq.getCenterFreq(b) / 1000; // Hz
                int mB = ganLatino(nombre, f);
                if (mB < min) mB = min; if (mB > max) mB = max;
                eq.setBandLevel(b, (short) mB);
            }
        } catch (Exception e) {}
    }
    private int ganLatino(String g, int f) {
        // ganancias en milibelios (100 mB = 1 dB)
        if (g.equals("Bachata")) {
            if (f < 80) return 300; if (f < 250) return 200; if (f < 600) return -150;
            if (f < 1500) return 0; if (f < 4000) return 350; if (f < 9000) return 450; return 350;
        } else if (g.equals("Merengue")) {
            if (f < 80) return 450; if (f < 250) return 300; if (f < 600) return -100;
            if (f < 1500) return 100; if (f < 4000) return 250; if (f < 9000) return 400; return 300;
        } else if (g.equals("Salsa")) {
            if (f < 80) return 250; if (f < 250) return 150; if (f < 600) return 0;
            if (f < 1500) return 250; if (f < 4000) return 300; if (f < 9000) return 300; return 200;
        }
        return 0;
    }
    private void configurarEq() {
        try {
            if (eq != null) { eq.release(); eq = null; }
            eq = new Equalizer(0, mp.getAudioSessionId());
            eq.setEnabled(eqEnabled);
            short bands = eq.getNumberOfBands();
            if (eqLevels != null && eqLevels.length == bands) {
                for (short b = 0; b < bands; b++) eq.setBandLevel(b, eqLevels[b]);
            }
        } catch (Exception e) { eq = null; }
    }

    private void mostrarEq() {
        if (mp == null || !prepared || eq == null) {
            Toast.makeText(this, "Pon una cancion primero para usar el ecualizador", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            final short bands = eq.getNumberOfBands();
            final short[] range = eq.getBandLevelRange();
            final short min = range[0], max = range[1];

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            final CheckBox chk = new CheckBox(this);
            chk.setText("Ecualizador activado");
            chk.setTextColor(Color.WHITE);
            chk.setChecked(eqEnabled);
            root.addView(chk);

            final ArrayList<String> presets = new ArrayList<String>();
            presets.add("Personalizado");
            presets.add("Bachata");
            presets.add("Merengue");
            presets.add("Salsa");
            final short nPre = eq.getNumberOfPresets();
            for (short i = 0; i < nPre; i++) presets.add(eq.getPresetName(i));
            final Spinner sp = new Spinner(this);
            ArrayAdapter<String> pa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, presets);
            sp.setAdapter(pa);
            root.addView(sp);

            final SeekBar[] sliders = new SeekBar[bands];
            for (short b = 0; b < bands; b++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView lbl = new TextView(this);
                int freq = eq.getCenterFreq(b) / 1000;
                lbl.setText(freq >= 1000 ? (freq / 1000) + "k" : freq + "");
                lbl.setTextColor(0xFF8B8B9A);
                lbl.setWidth((int) (48 * getResources().getDisplayMetrics().density));

                final SeekBar sb = new SeekBar(this);
                sb.setMax(max - min);
                sb.setProgress(eq.getBandLevel(b) - min);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                sb.setLayoutParams(lp);
                final short band = b;
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        if (!fromUser) return;
                        try { eq.setBandLevel(band, (short) (p + min)); } catch (Exception e) {}
                        sp.setSelection(0);
                    }
                    public void onStartTrackingTouch(SeekBar s) {}
                    public void onStopTrackingTouch(SeekBar s) {}
                });
                sliders[b] = sb;
                row.addView(lbl);
                row.addView(sb);
                root.addView(row);
            }

            chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                    eqEnabled = isChecked;
                    try { eq.setEnabled(isChecked); } catch (Exception e) {}
                }
            });

            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View v, int position, long idd) {
                    if (position == 0) return;
                    try {
                        if (!eqEnabled) { eqEnabled = true; chk.setChecked(true); try { eq.setEnabled(true); } catch (Exception e2) {} }
                        if (position >= 1 && position <= 3) {
                            aplicarPresetLatino((String) parent.getItemAtPosition(position));
                        } else {
                            eq.usePreset((short) (position - 4));
                        }
                        for (short b = 0; b < bands; b++) sliders[b].setProgress(eq.getBandLevel(b) - min);
                    } catch (Exception e) {}
                }
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            AlertDialog.Builder db = new AlertDialog.Builder(this);
            db.setTitle("Ecualizador");
            db.setView(root);
            db.setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        eqLevels = new short[bands];
                        for (short b = 0; b < bands; b++) {
                            eqLevels[b] = eq.getBandLevel(b);
                            if (b > 0) sb.append(",");
                            sb.append((int) eqLevels[b]);
                        }
                        prefs.edit().putString("eqBands", sb.toString()).putBoolean("eqOn", eqEnabled).apply();
                    } catch (Exception e) {}
                }
            });
            db.setNegativeButton("Cerrar", null);
            db.show();
        } catch (Exception e) {
            Toast.makeText(this, "Este equipo no permite el ecualizador", Toast.LENGTH_SHORT).show();
        }
    }

    private void mostrarPane(int p) {
        paneReproduciendo.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        paneLista.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        paneAjustes.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        paneExplorar.setVisibility(p == 3 ? View.VISIBLE : View.GONE);
        if (p == 2) pintarAjustes();
    }
    private void mostrarLista(boolean ver) { mostrarPane(ver ? 1 : 0); }

    @Override
    public void onBackPressed() {
        View pv = findViewById(R.id.paneVideo);
        if (pv != null && pv.getVisibility() == View.VISIBLE) { cerrarVideo(); return; }
        if (paneExplorar != null && paneExplorar.getVisibility() == View.VISIBLE) { mostrarPane(2); return; }
        if (paneAjustes != null && paneAjustes.getVisibility() == View.VISIBLE) { mostrarPane(0); return; }
        if (paneLista != null && paneLista.getVisibility() == View.VISIBLE) { atrasEnLista(); return; }
        super.onBackPressed();
    }

    // ---------- Ajustes ----------
    private boolean hayInternet() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }
    }
    private void pintarAjustes() {
        TextView tv = (TextView) findViewById(R.id.txtCarpetaVinc);
        Button bd = (Button) findViewById(R.id.btnDesvincular);
        if (carpetaVinculada != null) { tv.setText("Carpeta: " + carpetaVinculada); bd.setVisibility(View.VISIBLE); }
        else { tv.setText("(sin vincular — muestra toda la música)"); bd.setVisibility(View.GONE); }
        TextView net = (TextView) findViewById(R.id.txtNet);
        net.setText(hayInternet() ? "Internet: conectado" : "Internet: sin conexión");
    }

    // ---------- Explorador de carpetas ----------
    private void abrirExplorador() {
        ArrayList<File> base = raicesBase();
        expActual = base.isEmpty() ? new File("/") : base.get(0);
        pintarExplorador();
        mostrarPane(3);
    }
    private void pintarExplorador() {
        expItems.clear(); expDirs.clear();
        TextView ruta = (TextView) findViewById(R.id.txtRutaActual);
        ruta.setText(expActual != null ? expActual.getAbsolutePath() : "/");
        File parent = expActual != null ? expActual.getParentFile() : null;
        if (parent != null) { expItems.add(".. (subir)"); expDirs.add(parent); }
        File[] hijos = null;
        try { hijos = expActual.listFiles(); } catch (Exception e) {}
        if (hijos != null) {
            Arrays.sort(hijos, new Comparator<File>() {
                public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
            });
            for (File f : hijos) {
                try { if (f.isDirectory() && !f.getName().startsWith(".")) { expItems.add("[carpeta]  " + f.getName()); expDirs.add(f); } } catch (Exception e) {}
            }
        }
        expAdapter.notifyDataSetChanged();
        listExplorar.setSelection(0);
    }
    private void navegarExplorador(int pos) {
        if (pos < 0 || pos >= expDirs.size()) return;
        expActual = expDirs.get(pos);
        pintarExplorador();
    }
    private void usarCarpeta() {
        if (expActual == null) return;
        carpetaVinculada = expActual.getAbsolutePath();
        prefs.edit().putString("carpetaVinc", carpetaVinculada).apply();
        Toast.makeText(this, "Carpeta vinculada", Toast.LENGTH_SHORT).show();
        escanearMusica();
        mostrarPane(1);
    }

    // ---------- Descargar carátulas (iTunes) ----------
    private void descargarFaltantes() {
        final TextView prog = (TextView) findViewById(R.id.txtProgArt);
        if (!hayInternet()) { prog.setText("No hay internet"); return; }
        if (descargaEnCurso) return;
        descargaEnCurso = true;
        prog.setText("Buscando canciones sin carátula…");
        new Thread(new Runnable() {
            public void run() {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                try { Thread.sleep(1500); } catch (Exception e) {}   // respiro para que la música/UI arranquen fluido
                try {
                    final ArrayList<Song> todas = new ArrayList<Song>();
                    for (Carpeta c : carpetas) todas.addAll(c.songs);
                    int ok = 0;
                    for (int i = 0; i < todas.size(); i++) {
                        final Song s = todas.get(i); final int idx = i + 1; final int tot = todas.size();
                        runOnUiThread(new Runnable(){ public void run(){ prog.setText("Buscando " + idx + "/" + tot + "…"); }});
                        boolean cacheada; synchronized (artCache) { cacheada = artCache.containsKey(s.path); }
                        if (cacheada || tieneArtEmbebida(s)) continue;
                        byte[] img = descargarArt(s);
                        if (img != null) {
                            synchronized (artCache) { artCache.put(s.path, img); }
                            if (optEmbed) embedArt(s, img);
                            ok++;
                            final Song ss = s;
                            runOnUiThread(new Runnable(){ public void run(){ refrescarSiActual(ss); }});
                        }
                        try { Thread.sleep(300); } catch (Exception e) {}
                    }
                    final int fok = ok;
                    runOnUiThread(new Runnable(){ public void run(){ prog.setText(fok + " carátula(s) agregada(s)"); }});
                } finally {
                    descargaEnCurso = false;   // siempre se libera, aunque haya error
                }
            }
        }).start();
    }
    private boolean tieneArtEmbebida(Song s) {
        try { MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(s.path); byte[] p = r.getEmbeddedPicture(); try{r.release();}catch(Exception e){} return p != null; }
        catch (Exception e) { return false; }
    }
    private void refrescarSiActual(Song s) {
        if (posEnOrden >= 0 && posEnOrden < order.size() && order.get(posEnOrden) < songs.size()
            && songs.get(order.get(posEnOrden)).path.equals(s.path)) { cargarMetadatosActual(s); }
    }
    private byte[] descargarArt(Song s) {
        java.util.ArrayList<String> terminos = terminosBusqueda(s);
        // Probar cada término en iTunes y Deezer (del más específico al más simple)
        for (int i = 0; i < terminos.size(); i++) {
            String q = terminos.get(i);
            byte[] r = artDeItunes(q); if (r != null) return r;
            r = artDeDeezer(q);        if (r != null) return r;
        }
        // MusicBrainz al final (más lento), solo con el mejor término
        if (!terminos.isEmpty()) { byte[] r = artDeMusicBrainz(terminos.get(0)); if (r != null) return r; }
        return null;
    }
    // Arma varias búsquedas posibles, de la más precisa a la más simple
    // Devuelve el nombre para partir por guión: quita extensión y número de pista, PERO conserva los "-"
    private String nombreParaPartir(Song s) {
        String n = (s.title != null && s.title.trim().length() > 0) ? s.title : nombreDe(s);
        if (n == null) return "";
        int dot = n.lastIndexOf('.');
        if (dot > 0 && (n.length() - dot) <= 5) n = n.substring(0, dot);
        n = n.replaceAll("^\\s*\\d{1,3}\\s*[-_.)]+\\s*", "");   // quitar "01 - " del inicio
        n = n.replace('_', ' ');
        return n.trim();
    }
    private java.util.ArrayList<String> terminosBusqueda(Song s) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        String art = (s.artist != null && !s.artist.equalsIgnoreCase("Desconocido")) ? limpiarBusqueda(s.artist) : "";
        String titLimpio = limpiarBusqueda(s.title);

        // 1) Estructura del archivo "Artista - Canción": izquierda = ARTISTA, derecha = CANCIÓN
        String base = nombreParaPartir(s);
        if (base.indexOf("-") >= 0) {
            String[] pz = base.split("\\s*-\\s*");
            if (pz.length >= 2) {
                String artistaIzq = limpiarBusqueda(pz[0]);                 // izquierda
                String cancionDer = limpiarBusqueda(pz[pz.length - 1]);     // derecha
                if (artistaIzq.length() > 0 && cancionDer.length() > 0) set.add(artistaIzq + " " + cancionDer); // artista + canción (lo más preciso)
                if (cancionDer.length() > 0) set.add(cancionDer);           // solo la canción
                if (artistaIzq.length() > 0) set.add(artistaIzq);          // solo el artista
            }
        }
        // 2) De los tags reales (si existen)
        if (art.length() > 0 && titLimpio.length() > 0) set.add(art + " " + titLimpio);
        if (s.album != null && s.album.length() > 0 && art.length() > 0) set.add(art + " " + limpiarBusqueda(s.album));
        if (titLimpio.length() > 0) set.add(titLimpio);
        // 3) nombre completo limpio como último recurso
        String fileLimpio = limpiarBusqueda(nombreDe(s));
        if (fileLimpio.length() > 0) set.add(fileLimpio);

        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String q : set) { if (q != null && q.trim().length() >= 3 && out.size() < 6) out.add(q.trim()); }
        return out;
    }
    // Limpia un texto para buscar: quita nº de pista, extensión, (adornos), y palabras de ruido
    private String limpiarBusqueda(String n) {
        if (n == null) return "";
        String x = n;
        int dot = x.lastIndexOf('.');
        if (dot > 0 && (x.length() - dot) <= 5) x = x.substring(0, dot);       // quitar .mp3
        x = x.replaceAll("^\\s*\\d{1,3}\\s*[-_.)]+\\s*", "");                    // quitar "01 - ", "07-", "12."
        x = x.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^\\]]*\\]", " ");    // quitar (en vivo) [video]
        x = x.replace('_', ' ').replace('-', ' ');
        x = x.replaceAll("(?i)\\d+\\s*kbps", " ");                                // 320kbps, 128 kbps
        x = x.replaceAll("(?i)\\b(official|oficial|video|audio|lyrics?|letra|hd|hq|4k|en vivo|live|version|remaster(ed|izado)?|full|mp3|mp4|kbps|descargar|descarga|www|com|net|online|gratis|calidad|estreno|completa|original)\\b", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }
    private String pxCal() { return optCalidad.equals("maxima") ? "1200x1200" : optCalidad.equals("normal") ? "300x300" : "600x600"; }
    private byte[] artDeItunes(String term) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            // HTTP primero (el radio viejo no puede con el TLS moderno); HTTPS de respaldo
            String json = httpGet("http://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + q);
            if (json == null) json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + q);
            if (json == null) return null;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("results");
            if (arr == null || arr.length() == 0) return null;
            String art = arr.getJSONObject(0).optString("artworkUrl100", "");
            if (art.length() == 0) return null;
            art = art.replace("100x100", pxCal());
            String artHttp = art.startsWith("https://") ? "http://" + art.substring(8) : art;
            byte[] img = httpGetBytes(artHttp);
            if (img == null && !artHttp.equals(art)) img = httpGetBytes(art);  // respaldo HTTPS
            return img;
        } catch (Exception e) { return null; }
    }
    private byte[] artDeDeezer(String term) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("http://api.deezer.com/search?limit=1&q=" + q);
            if (json == null) json = httpGet("https://api.deezer.com/search?limit=1&q=" + q);
            if (json == null) return null;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("data");
            if (arr == null || arr.length() == 0) return null;
            org.json.JSONObject alb = arr.getJSONObject(0).optJSONObject("album");
            if (alb == null) return null;
            String key = optCalidad.equals("maxima") ? "cover_xl" : optCalidad.equals("normal") ? "cover_medium" : "cover_big";
            String art = alb.optString(key, alb.optString("cover_big", alb.optString("cover", "")));
            if (art.length() == 0) return null;
            String artHttp = art.startsWith("https://") ? "http://" + art.substring(8) : art;
            byte[] img = httpGetBytes(artHttp);
            if (img == null && !artHttp.equals(art)) img = httpGetBytes(art);  // respaldo HTTPS
            return img;
        } catch (Exception e) { return null; }
    }
    private byte[] artDeMusicBrainz(String term) {
        try {
            String url = "https://musicbrainz.org/ws/2/release/?fmt=json&limit=1&query=" + URLEncoder.encode(term, "UTF-8");
            String json = httpGet(url); if (json == null) return null;
            org.json.JSONArray rel = new org.json.JSONObject(json).optJSONArray("releases");
            if (rel == null || rel.length() == 0) return null;
            String mbid = rel.getJSONObject(0).optString("id", "");
            if (mbid.length() == 0) return null;
            String size = optCalidad.equals("normal") ? "250" : "500";
            return httpGetBytes("https://coverartarchive.org/release/" + mbid + "/front-" + size);
        } catch (Exception e) { return null; }
    }
    private HttpURLConnection abrirSiguiendo(String u) throws Exception {
        String cur = u;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = abrir(cur);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.length() == 0) return abrir(cur);
                if (loc.startsWith("/")) { URL b = new URL(cur); loc = b.getProtocol() + "://" + b.getHost() + loc; }
                cur = loc;
                continue;
            }
            return c;
        }
        return abrir(cur);
    }
    private String ultimoError = "";
    private String httpGet(String u) {
        try {
            HttpURLConnection c = abrirSiguiendo(u);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { ultimoError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""); return null; }
    }
    private byte[] httpGetBytes(String u) {
        try {
            HttpURLConnection c = abrirSiguiendo(u);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }
    private HttpURLConnection abrir(String u) throws Exception {
        URL url = new URL(u);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(9000); c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/40 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "*/*");
        if (c instanceof HttpsURLConnection) {
            if (conscryptOk && conscryptFactory != null) {
                // Conscrypt cargó: usar su TLS moderno (como Poweramp)
                try { ((HttpsURLConnection) c).setSSLSocketFactory(conscryptFactory); } catch (Exception e) {}
            } else if (Build.VERSION.SDK_INT < 21) {
                // Respaldo: si Conscrypt no cargó, intentar con el candado del sistema
                try { ((HttpsURLConnection) c).setSSLSocketFactory(new Tls12SocketFactory()); } catch (Exception e) {}
            }
        }
        return c;
    }

    // ---------- Escribir carátula dentro del MP3 (ID3v2.3) ----------
    private boolean embedArt(Song s, byte[] img) {
        try {
            if (!s.path.toLowerCase(Locale.US).endsWith(".mp3")) return false;
            File f = new File(s.path);
            // Averiguar el tamaño del tag ID3 viejo leyendo SOLO los primeros 10 bytes (no todo el archivo)
            int start = 0;
            FileInputStream head = new FileInputStream(f);
            byte[] h = new byte[10];
            int leidos = head.read(h);
            head.close();
            if (leidos == 10 && (h[0] & 0xff) == 0x49 && (h[1] & 0xff) == 0x44 && (h[2] & 0xff) == 0x33) {
                start = 10 + ((h[6] & 127) * 2097152 + (h[7] & 127) * 16384 + (h[8] & 127) * 128 + (h[9] & 127));
            }
            byte[] tag = construirTag(s, img);
            // Escribir a un archivo temporal por PEDAZOS (streaming), sin cargar el MP3 completo en memoria
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileInputStream in = new FileInputStream(f);
            long porSaltar = start;
            while (porSaltar > 0) { long sk = in.skip(porSaltar); if (sk <= 0) break; porSaltar -= sk; }
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(tag);
            byte[] chunk = new byte[65536];  // 64 KB por vuelta
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            in.close(); out.flush(); out.close();
            // Reemplazar el original por el temporal
            if (f.delete() && tmp.renameTo(f)) return true;
            // Si el rename falla, intentar copiar de vuelta
            tmp.delete();
            return false;
        } catch (Throwable t) { return false; }
    }
    private byte[] leerArchivo(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
    private byte[] construirTag(Song s, byte[] img) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (s.title != null) body.write(frameTexto("TIT2", s.title));
        if (s.artist != null && s.artist.length() > 0) body.write(frameTexto("TPE1", s.artist));
        if (s.album != null && s.album.length() > 0) body.write(frameTexto("TALB", s.album));
        if (img != null) body.write(frameApic(img));
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{ 0x49, 0x44, 0x33, 3, 0, 0 }); // "ID3" v2.3
        out.write(new byte[]{ (byte)((b.length >> 21) & 127), (byte)((b.length >> 14) & 127), (byte)((b.length >> 7) & 127), (byte)(b.length & 127) });
        out.write(b);
        return out.toByteArray();
    }
    private byte[] frameTexto(String id, String txt) throws Exception {
        byte[] t = txt.getBytes("UTF-8");
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(3); d.write(t); // encoding UTF-8
        return frame(id, d.toByteArray());
    }
    private byte[] frameApic(byte[] img) throws Exception {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(0); // encoding latin1
        d.write("image/jpeg".getBytes("ISO-8859-1")); d.write(0); // mime + null
        d.write(3); // tipo: portada frontal
        d.write(0); // descripción vacía + null
        d.write(img);
        return frame("APIC", d.toByteArray());
    }
    private byte[] frame(String id, byte[] data) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id.getBytes("ISO-8859-1"));
        int L = data.length;
        o.write(new byte[]{ (byte)((L >> 24) & 255), (byte)((L >> 16) & 255), (byte)((L >> 8) & 255), (byte)(L & 255) });
        o.write(new byte[]{ 0, 0 });
        o.write(data);
        return o.toByteArray();
    }

    private void aplicarEfectos() {
        boolean sonando = (mp != null && prepared && mp.isPlaying());
        boolean anillos = sonando && (efectoModo == 0 || efectoModo == 3);
        boolean parts   = sonando && (efectoModo == 1 || efectoModo == 3);
        boolean brillo  = sonando && (efectoModo == 2 || efectoModo == 3);
        try { if (vizBg != null) { if (anillos) vizBg.iniciar(); else vizBg.parar(); } } catch (Exception e) {}
        try { if (particles != null) { particles.setParts(parts); particles.setBrillo(brillo); if (parts || brillo) particles.iniciar(); else particles.parar(); } } catch (Exception e) {}
        // Efecto "Nombre + ecualizador" (modo 5)
        try {
            if (eqNombre != null) {
                boolean on = (efectoModo == 5);
                if (on) {
                    Song s = cancionActual();
                    if (s != null) eqNombre.setInfo(s.artist != null && s.artist.length() > 0 ? s.artist : "", s.title);
                }
                eqNombre.setSonando(sonando);
                eqNombre.setActivo(on);
            }
        } catch (Exception e) {}
    }
    private void attachVisualizer() {
        try {
            if (visualizer != null) {
                try { visualizer.setEnabled(false); } catch (Exception e) {}
                try { visualizer.release(); } catch (Exception e) {}
                visualizer = null;
            }
            if (mp == null) return;
            visualizer = new Visualizer(mp.getAudioSessionId());
            int[] rango = Visualizer.getCaptureSizeRange();
            int cap = 256; if (cap < rango[0]) cap = rango[0]; if (cap > rango[1]) cap = rango[1];
            visualizer.setCaptureSize(cap);
            int rate = (Visualizer.getMaxCaptureRate() * 3) / 4;
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] wave, int r) {}
                public void onFftDataCapture(Visualizer v, byte[] data, int r) { if (vizBg != null) vizBg.setFft(data); if (particles != null) particles.setFft(data); if (eqNombre != null) eqNombre.setFft(data); }
            }, rate, false, true);
            visualizer.setEnabled(true);
        } catch (Throwable t) { visualizer = null; }
    }

    private void aplicarTema() {
        if (optTema.equals("azul")) accent = 0xFF3B82F6;
        else if (optTema.equals("rojo")) accent = 0xFFEF4444;
        else if (optTema.equals("verde")) accent = 0xFF22C55E;
        else accent = 0xFFFFB020;
        try { ((Button) findViewById(R.id.btnEq)).setTextColor(0xFFFFFFFF); } catch (Exception e) {}
        try { pintarFav(); } catch (Exception e) {}
        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
        try { pintarSegCalidad(); pintarSegOrden(); } catch (Exception e) {}
        try { if (vizBg != null) vizBg.setColor(accent); } catch (Exception e) {}
        try { if (particles != null) particles.setColor(accent); } catch (Exception e) {}
    }
    private void pintarSegCalidad() {
        int[] ids = { R.id.calNormal, R.id.calAlta, R.id.calMaxima };
        String[] vals = { "normal", "alta", "maxima" };
        for (int i = 0; i < ids.length; i++) { Button b = (Button) findViewById(ids[i]); boolean on = vals[i].equals(optCalidad); b.setBackgroundColor(on ? accent : 0xFF1C1C26); b.setTextColor(on ? 0xFF0A0A0E : 0xFF8B8B9A); }
    }
    private void pintarSegOrden() {
        int[] ids = { R.id.ordNombre, R.id.ordArtista, R.id.ordFecha };
        String[] vals = { "nombre", "artista", "fecha" };
        for (int i = 0; i < ids.length; i++) { Button b = (Button) findViewById(ids[i]); boolean on = vals[i].equals(optOrden); b.setBackgroundColor(on ? accent : 0xFF1C1C26); b.setTextColor(on ? 0xFF0A0A0E : 0xFF8B8B9A); }
    }
    private void ordenarSongs(ArrayList<Song> arr) {
        Collections.sort(arr, new Comparator<Song>() {
            public int compare(Song a, Song b) {
                if (optOrden.equals("artista")) return (a.artist == null ? "" : a.artist).compareToIgnoreCase(b.artist == null ? "" : b.artist);
                if (optOrden.equals("fecha")) return (b.fecha < a.fecha) ? -1 : (b.fecha > a.fecha ? 1 : 0);
                return (a.title == null ? "" : a.title).compareToIgnoreCase(b.title == null ? "" : b.title);
            }
        });
    }
    private void reordenar() {
        for (Carpeta c : carpetas) ordenarSongs(c.songs);
        adapter.notifyDataSetChanged();
    }

    private void aplicarPantalla() {
        if (optPantalla) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            liberarWake();
        }
    }
    private void tomarWake() {
        if (!optPantalla) return;
        try {
            if (wakeCpu == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeCpu = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SonidoJFV:play");
            }
            if (!wakeCpu.isHeld()) wakeCpu.acquire();
        } catch (Exception e) {}
    }
    private void liberarWake() { try { if (wakeCpu != null && wakeCpu.isHeld()) wakeCpu.release(); } catch (Exception e) {} }

    private void registrarUsbReceiver() {
        try {
            usbReceiver = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, android.content.Intent i) {
                    String a = i.getAction();
                    if (a == null) return;
                    if (a.equals(android.content.Intent.ACTION_MEDIA_MOUNTED)) {
                        // USB CONECTADO: enfocar en el USB (ignora la interna) si la opción está encendida
                        if (optAutoDetectar) {
                            Toast.makeText(MainActivity.this, "USB detectado, enfocando…", Toast.LENGTH_SHORT).show();
                            handler.postDelayed(new Runnable(){ public void run(){ detectarYEnfocarUsb(); } }, 1800);
                        }
                    } else {
                        // USB DESCONECTADO: cortar si está sonando y reescanear para quitar lo que ya no está
                        if (optPausaUsb && mp != null) {
                            try { mp.stop(); } catch (Exception e) {}
                            prepared = false; pintarPlay(false);
                        }
                        handler.postDelayed(new Runnable(){ public void run(){ escanearMusica(); } }, 800);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_MEDIA_MOUNTED);
            f.addAction(android.content.Intent.ACTION_MEDIA_EJECT);
            f.addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(android.content.Intent.ACTION_MEDIA_REMOVED);
            f.addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL);
            f.addAction(android.content.Intent.ACTION_MEDIA_CHECKING);
            f.addDataScheme("file");
            registerReceiver(usbReceiver, f);
            // Segundo receptor sin scheme (algunos radios no mandan el "file")
            android.content.IntentFilter f2 = new android.content.IntentFilter();
            f2.addAction(android.content.Intent.ACTION_MEDIA_MOUNTED);
            f2.addAction(android.content.Intent.ACTION_MEDIA_EJECT);
            f2.addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED);
            f2.addAction(android.content.Intent.ACTION_MEDIA_REMOVED);
            f2.addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL);
            registerReceiver(usbReceiver, f2);
        } catch (Exception e) {}
    }

    private void registrarNetReceiver() {
        try {
            netReceiver = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, android.content.Intent i) {
                    if (optAuto && !descargaEnCurso && hayInternet() && !carpetas.isEmpty()) descargarFaltantes();
                }
            };
            registerReceiver(netReceiver, new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception e) {}
    }

    // Cambiar carátula manual (tocando la carátula): buscar y elegir
    // ---- Reproductor de video ----
    private android.widget.VideoView videoView;
    private void abrirVideo(int position) {
        if (position < 0 || position >= videos.size()) return;
        final Song vid = videos.get(position);
        // Cortar la música de verdad (pausar + parar el actualizador + soltar foco de audio)
        try { if (mp != null && prepared) { mp.pause(); } } catch (Exception e) {}
        pintarPlay(false);
        try { handler.removeCallbacks(actualizador); } catch (Exception e) {}
        try { if (am != null) am.abandonAudioFocus(focoListener); } catch (Exception e) {}
        final View pane = findViewById(R.id.paneVideo);
        if (videoView == null) {
            videoView = (android.widget.VideoView) findViewById(R.id.videoView);
            android.widget.MediaController mc = new android.widget.MediaController(this);
            mc.setAnchorView(videoView);
            videoView.setMediaController(mc);
            videoView.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener(){
                public void onPrepared(android.media.MediaPlayer m){
                    // Cortar la música JUSTO cuando el video va a empezar (evita que suenen los dos)
                    try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception e) {}
                    try { handler.removeCallbacks(actualizador); } catch (Exception e) {}
                    try { if (am != null) am.abandonAudioFocus(focoListener); } catch (Exception e) {}
                    pintarPlay(false);
                    try { m.start(); } catch (Exception e) {}
                }
            });
            videoView.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener(){
                public boolean onError(android.media.MediaPlayer m, int a, int b){
                    Toast.makeText(MainActivity.this, "Este video no se puede reproducir en el radio (formato no compatible)", Toast.LENGTH_LONG).show();
                    cerrarVideo();
                    return true;
                }
            });
            videoView.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener(){
                public void onCompletion(android.media.MediaPlayer m){ cerrarVideo(); }
            });
        }
        pane.setVisibility(View.VISIBLE);
        try {
            videoView.setVideoPath(vid.path);
            videoView.requestFocus();
            videoView.start();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el video", Toast.LENGTH_SHORT).show();
            cerrarVideo();
        }
    }
    private void cerrarVideo() {
        try {
            if (videoView != null) {
                videoView.stopPlayback();                                    // detiene y libera el reproductor de video
                try { videoView.setVideoURI(null); } catch (Exception e) {}  // suelta el archivo por completo
            }
        } catch (Exception e) {}
        videoEstabaReproduciendo = false;   // que la reversa NO lo reviva
        videoPosGuardada = 0;
        View pane = findViewById(R.id.paneVideo);
        if (pane != null) pane.setVisibility(View.GONE);
    }
    // Menú al dejar presionado un video: eliminar del USB
    private void opcionesVideo(final int position) {
        if (position < 0 || position >= videos.size()) return;
        final Song vid = videos.get(position);
        new AlertDialog.Builder(this)
            .setTitle("Eliminar video")
            .setMessage("¿Borrar este video del USB?\n\n" + nombreDe(vid) + "\n\nEsto no se puede deshacer.")
            .setPositiveButton("Eliminar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    boolean borrado = false;
                    try { borrado = new File(vid.path).delete(); } catch (Exception e) {}
                    if (borrado) {
                        videos.remove(position);
                        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                        actualizarHeaderLista();
                        Toast.makeText(MainActivity.this, "Video eliminado", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "No se pudo borrar (¿USB de solo lectura?)", Toast.LENGTH_LONG).show();
                    }
                }
            }).setNegativeButton("Cancelar", null).show();
    }

    // Menú al dejar presionada una canción en una carpeta
    private void opcionesCancion(final int p) {
        if (p < 0 || p >= cancionesCarpeta.size()) return;
        new AlertDialog.Builder(this).setItems(new String[]{ "Corregir nombre y carátula", "Mover a otra carpeta", "Eliminar del USB" },
            new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (w == 0) sugerirNombre(cancionesCarpeta.get(p));
                    else if (w == 1) moverCancion(p);
                    else borrarCancion(p);
                }
            }).show();
    }
    // Elegir carpeta destino y mover el archivo
    private void moverCancion(final int p) {
        if (p < 0 || p >= cancionesCarpeta.size()) return;
        final Song s = cancionesCarpeta.get(p);
        final ArrayList<Carpeta> destinos = new ArrayList<Carpeta>();
        for (Carpeta c : carpetas) {
            if (c.esLista || c.path == null) continue;
            if (carpetaAbierta != null && carpetaAbierta.path != null && c.path.equals(carpetaAbierta.path)) continue;
            destinos.add(c);
        }
        if (destinos.isEmpty()) { Toast.makeText(this, "No hay otra carpeta a donde mover", Toast.LENGTH_SHORT).show(); return; }
        String[] nombres = new String[destinos.size()];
        for (int i = 0; i < destinos.size(); i++) nombres[i] = destinos.get(i).name;
        new AlertDialog.Builder(this).setTitle("Mover a…").setItems(nombres, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { moverArchivo(s, destinos.get(w)); }
        }).show();
    }
    private void moverArchivo(final Song s, final Carpeta dest) {
        Toast.makeText(this, "Moviendo…", Toast.LENGTH_SHORT).show();
        // Si es la que suena, detenerla para poder mover el archivo
        Song son = cancionActual();
        if (son != null && son.path != null && son.path.equals(s.path)) {
            try { if (mp != null) { mp.stop(); mp.reset(); } } catch (Exception e) {}
            prepared = false; pintarPlay(false);
        }
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    File orig = new File(s.path);
                    File cd = new File(dest.path); if (!cd.exists()) cd.mkdirs();
                    File nuevo = new File(cd, orig.getName());
                    int n = 1;
                    while (nuevo.exists()) { n++; nuevo = new File(cd, baseNombre(orig.getName()) + "_" + n + extNombre(orig.getName())); }
                    ok = orig.renameTo(nuevo);
                    if (!ok) { if (copiarArchivo(orig, nuevo)) { orig.delete(); ok = true; } }
                    if (ok) s.path = nuevo.getAbsolutePath();
                } catch (Throwable t) { ok = false; }
                final boolean res = ok;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (res) { Toast.makeText(MainActivity.this, "Movida a " + dest.name, Toast.LENGTH_SHORT).show(); escanearMusica(); }
                        else Toast.makeText(MainActivity.this, "No se pudo mover (¿USB de solo lectura?)", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
    private boolean copiarArchivo(File a, File b) {
        try {
            FileInputStream in = new FileInputStream(a);
            FileOutputStream out = new FileOutputStream(b);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.flush(); out.close(); return true;
        } catch (Throwable t) { return false; }
    }
    private String baseNombre(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }
    private String extNombre(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(d) : ""; }

    // Borra la canción que está sonando (desde la pantalla principal) y pasa a la siguiente
    private void borrarCancionActual() {
        final Song s = cancionActual();
        if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        final String nom = nombreDe(s);
        new AlertDialog.Builder(this)
            .setTitle("Eliminar canción")
            .setMessage("¿Borrar este archivo del USB?\n\n" + nom + "\n\nEsto no se puede deshacer.")
            .setPositiveButton("Eliminar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    // Guardar cuál es la siguiente ANTES de borrar
                    Song sig = null;
                    if (order.size() > 1) {
                        int np = posEnOrden + 1; if (np >= order.size()) np = 0;
                        if (np < order.size() && order.get(np) < songs.size()) sig = songs.get(order.get(np));
                        if (sig == s) sig = null;
                    }
                    try { if (mp != null) { mp.stop(); mp.reset(); } } catch (Exception e) {}
                    prepared = false; pintarPlay(false);
                    boolean borrado = false;
                    try { borrado = new File(s.path).delete(); } catch (Exception e) {}
                    if (!borrado) { Toast.makeText(MainActivity.this, "No se pudo borrar (¿USB de solo lectura?)", Toast.LENGTH_LONG).show(); return; }
                    // Quitar de todas las listas en memoria
                    for (int i = songs.size() - 1; i >= 0; i--) { if (songs.get(i).path != null && songs.get(i).path.equals(s.path)) songs.remove(i); }
                    for (int i = cancionesCarpeta.size() - 1; i >= 0; i--) { if (cancionesCarpeta.get(i).path != null && cancionesCarpeta.get(i).path.equals(s.path)) cancionesCarpeta.remove(i); }
                    construirOrden();
                    Toast.makeText(MainActivity.this, "Canción eliminada", Toast.LENGTH_SHORT).show();
                    if (!songs.isEmpty()) {
                        if (sig != null) { int ix = songs.indexOf(sig); posEnOrden = (ix >= 0) ? order.indexOf(ix) : 0; }
                        else { if (posEnOrden >= order.size()) posEnOrden = 0; }
                        if (posEnOrden < 0) posEnOrden = 0;
                        cargarYReproducir();
                    }
                    try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                    actualizarHeaderLista();
                }
            }).setNegativeButton("Cancelar", null).show();
    }
    // Normaliza para búsqueda: minúsculas, sin acentos, sin guiones/puntos
    private String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.US);
        try { s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); } catch (Throwable t) {}
        // TODO lo que no sea letra/número/ñ -> espacio (maneja . - _ | / etc.)
        s = s.replaceAll("[^a-z0-9ñ]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }
    private static final java.util.HashSet<String> BASURA = new java.util.HashSet<String>(java.util.Arrays.asList(
        "www","http","https","com","net","org","web","mp3","mp4","wav","flac","m4a",
        "descargar","download","gratis","online","free","oficial","official","video","audio",
        "hd","hq","4k","lyrics","lyric","letra","letras","full","completo","remix","xd"));
    // Limpia texto para búsqueda: quita acentos, símbolos y palabras basura (web, com, descargar, etc.)
    private String limpiarTexto(String s) {
        String[] w = norm(s).split(" ");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            if (x.length() == 0 || BASURA.contains(x)) continue;
            if (x.matches("\\d{1,3}")) continue;  // números de pista sueltos
            b.append(x).append(' ');
        }
        return b.toString().trim();
    }
    // Buscar canción por VOZ (manos libres, para usar manejando)
    private static final int REQ_VOZ = 7001;
    private void buscarPorVoz() {
        try {
            android.content.Intent it = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "es-DO");
            it.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Di el nombre de la canción o artista");
            startActivityForResult(it, REQ_VOZ);
        } catch (Throwable t) {
            Toast.makeText(this, "Este radio no tiene reconocimiento de voz (falta la app de Google)", Toast.LENGTH_LONG).show();
        }
    }
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOZ && res == RESULT_OK && data != null) {
            try {
                java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) {
                    String dicho = r.get(0);
                    // mostrar en el buscador y filtrar
                    EditText et = (EditText) findViewById(R.id.etBuscar);
                    if (et != null) et.setText(dicho);
                    filtrarBusqueda(dicho);
                    // Si hay una sola coincidencia clara, reproducirla directo
                    if (cancionesCarpeta != null && cancionesCarpeta.size() >= 1) {
                        Toast.makeText(this, "Buscando: " + dicho, Toast.LENGTH_SHORT).show();
                        // reproducir la primera coincidencia
                        reproducirDeCarpeta(0);
                    } else {
                        Toast.makeText(this, "No encontré: " + dicho, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {}
        }
    }
    // Busca canciones por nombre/artista/álbum/archivo en TODAS las carpetas (por palabras, sin acentos)
    private void filtrarBusqueda(String qraw) {
        String q = limpiarTexto(qraw);
        if (q.length() == 0) {
            enBusqueda = false;
            if (modo == 1 && carpetaAbierta == carpetaBusqueda) { modo = 0; carpetaAbierta = null; }
            adapter.notifyDataSetChanged();
            actualizarHeaderLista();
            return;
        }
        enBusqueda = true;
        String[] palabras = q.split(" ");
        String qJunto = q.replace(" ", "");
        ArrayList<Song> res = new ArrayList<Song>();
        for (Carpeta c : carpetas) {
            for (Song s : c.songs) {
                String texto = limpiarTexto((s.title != null ? s.title : "") + " " + (s.artist != null ? s.artist : "") + " " + (s.album != null ? s.album : "") + " " + nombreDe(s));
                boolean todas = true;
                for (int k = 0; k < palabras.length; k++) { if (palabras[k].length() > 0 && texto.indexOf(palabras[k]) < 0) { todas = false; break; } }
                // También aceptar si el nombre pegado (sin espacios) contiene la búsqueda pegada
                if (!todas && qJunto.length() >= 3 && texto.replace(" ", "").indexOf(qJunto) >= 0) todas = true;
                if (todas) res.add(s);
            }
        }
        if (carpetaBusqueda == null) { carpetaBusqueda = new Carpeta(); carpetaBusqueda.name = "Búsqueda"; carpetaBusqueda.esLista = false; }
        carpetaBusqueda.songs = res;
        cancionesCarpeta = res;
        carpetaAbierta = carpetaBusqueda;
        modo = 1;
        adapter.notifyDataSetChanged();
        try { list.setSelection(0); } catch (Exception e) {}
        try { txtCount.setText("Resultados: " + res.size()); } catch (Exception e) {}
    }

    // Trae VARIAS URLs de carátula (iTunes + Deezer) para poder elegir otra distinta
    private void urlsDeItunes(String term, java.util.ArrayList<String> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("http://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + q);
            if (json == null) json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + q);
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("results");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                String art = arr.getJSONObject(i).optString("artworkUrl100", "");
                if (art.length() > 0) { art = art.replace("100x100", pxCal()); if (!out.contains(art)) out.add(art); }
            }
        } catch (Exception e) {}
    }
    private void urlsDeDeezer(String term, java.util.ArrayList<String> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("http://api.deezer.com/search?limit=8&q=" + q);
            if (json == null) json = httpGet("https://api.deezer.com/search?limit=8&q=" + q);
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("data");
            if (arr == null) return;
            String key = optCalidad.equals("maxima") ? "cover_xl" : optCalidad.equals("normal") ? "cover_medium" : "cover_big";
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject alb = arr.getJSONObject(i).optJSONObject("album");
                if (alb == null) continue;
                String art = alb.optString(key, alb.optString("cover_big", alb.optString("cover", "")));
                if (art.length() > 0 && !out.contains(art)) out.add(art);
            }
        } catch (Exception e) {}
    }
    private java.util.ArrayList<String> candidatosUrls(Song s) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.ArrayList<String> terminos = terminosBusqueda(s);
        for (int i = 0; i < terminos.size() && out.size() < 15; i++) {
            urlsDeItunes(terminos.get(i), out);
            urlsDeDeezer(terminos.get(i), out);
        }
        return out;
    }
    private byte[] bajarUrl(String art) {
        if (art == null || art.length() == 0) return null;
        String artHttp = art.startsWith("https://") ? "http://" + art.substring(8) : art;
        byte[] img = httpGetBytes(artHttp);
        if (img == null && !artHttp.equals(art)) img = httpGetBytes(art);
        return img;
    }

    // 4 toques: busca la carátula. Si repites, va CAMBIANDO a otra distinta ("Carátula 2 de 6"...)
    private void buscarCaratulaDirecto() {
        final Song s = cancionActual();
        if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        if (!hayInternet()) { Toast.makeText(this, "Sin internet", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Buscando carátula…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                java.util.ArrayList<String> urls = candidatosArt.get(s.path);
                if (urls == null || urls.isEmpty()) {
                    urls = candidatosUrls(s);
                    if (urls != null && !urls.isEmpty()) { candidatosArt.put(s.path, urls); indiceArt.put(s.path, -1); }
                }
                final java.util.ArrayList<String> lista = urls;
                if (lista == null || lista.isEmpty()) {
                    runOnUiThread(new Runnable(){ public void run(){ Toast.makeText(MainActivity.this, "No se encontró carátula para \"" + nombreDe(s) + "\"", Toast.LENGTH_LONG).show(); }});
                    return;
                }
                int idx = indiceArt.containsKey(s.path) ? indiceArt.get(s.path) : -1;
                byte[] img = null; int intentos = 0;
                while (intentos < lista.size()) {
                    idx = (idx + 1) % lista.size();
                    img = bajarUrl(lista.get(idx));
                    intentos++;
                    if (img != null) break;
                }
                indiceArt.put(s.path, idx);
                final byte[] fimg = img; final int fidx = idx; final int total = lista.size();
                if (fimg != null) { artCache.put(s.path, fimg); if (optEmbed) embedArt(s, fimg); }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fimg != null) {
                            refrescarSiActual(s);
                            try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                            Toast.makeText(MainActivity.this, "Carátula " + (fidx + 1) + " de " + total + " (4 toques = otra)", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "No se pudo descargar la carátula", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }
    private void cambiarCaratula() {
        final Song s = cancionActual();
        if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etTit = new EditText(this);
        etTit.setHint("Nombre de la canción");
        etTit.setText(s.title != null ? s.title : "");
        final EditText etArt = new EditText(this);
        etArt.setHint("Artista");
        etArt.setText(s.artist != null && !s.artist.equals("Desconocido") ? s.artist : "");
        box.addView(etTit);
        box.addView(etArt);
        Button btnSug = new Button(this);
        btnSug.setText("Sugerir nombre correcto (internet)");
        box.addView(btnSug);
        final android.app.AlertDialog dlgCar = new AlertDialog.Builder(this).setTitle("Carátula y nombre").setView(box)
            .setPositiveButton("Ver carátulas disponibles", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    buscarCaratulas(s, etTit.getText().toString().trim(), etArt.getText().toString().trim());
                }
            })
            .setNeutralButton("Solo guardar nombre", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    guardarNombre(s, etTit.getText().toString().trim(), etArt.getText().toString().trim());
                }
            })
            .setNegativeButton("Cancelar", null).show();
        btnSug.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { try { dlgCar.dismiss(); } catch (Exception e) {} sugerirNombre(s); }
        });
    }
    private byte[] artActual(Song s) {
        if (artCache.containsKey(s.path)) return artCache.get(s.path);
        try { MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(s.path); byte[] p = r.getEmbeddedPicture(); try { r.release(); } catch (Exception e) {} return p; } catch (Exception e) { return null; }
    }
    // ==== SUGERIR NOMBRE CORRECTO (busca en iTunes/Deezer y muestra opciones con carátula) ====
    private void sugItunes(String term, java.util.ArrayList<String[]> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("http://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + q);
            if (json == null) json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + q);
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("results");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String tit = o.optString("trackName", "");
                String art = o.optString("artistName", "");
                String url = o.optString("artworkUrl100", "").replace("100x100", pxCal());
                if (tit.length() > 0) out.add(new String[]{ tit, art, url });
            }
        } catch (Exception e) {}
    }
    private void sugDeezer(String term, java.util.ArrayList<String[]> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("http://api.deezer.com/search?limit=8&q=" + q);
            if (json == null) json = httpGet("https://api.deezer.com/search?limit=8&q=" + q);
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("data");
            if (arr == null) return;
            String key = optCalidad.equals("maxima") ? "cover_xl" : optCalidad.equals("normal") ? "cover_medium" : "cover_big";
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String tit = o.optString("title", "");
                org.json.JSONObject a = o.optJSONObject("artist"); String art = (a != null) ? a.optString("name", "") : "";
                org.json.JSONObject alb = o.optJSONObject("album");
                String url = (alb != null) ? alb.optString(key, alb.optString("cover_big", "")) : "";
                if (tit.length() > 0) out.add(new String[]{ tit, art, url });
            }
        } catch (Exception e) {}
    }
    // Tercera fuente: MusicBrainz (nombres) + Cover Art Archive (carátulas). Gratis y enorme.
    private void sugMusicBrainz(String term, java.util.ArrayList<String[]> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("https://musicbrainz.org/ws/2/recording/?query=" + q + "&fmt=json&limit=6");
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("recordings");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String tit = o.optString("title", "");
                String art = "";
                org.json.JSONArray ac = o.optJSONArray("artist-credit");
                if (ac != null && ac.length() > 0) art = ac.getJSONObject(0).optString("name", "");
                String url = "";
                org.json.JSONArray rel = o.optJSONArray("releases");
                if (rel != null && rel.length() > 0) {
                    String mbid = rel.getJSONObject(0).optString("id", "");
                    if (mbid.length() > 0) url = "https://coverartarchive.org/release/" + mbid + "/front-250";
                }
                if (tit.length() > 0) out.add(new String[]{ tit, art, url });
            }
        } catch (Exception e) {}
    }
    private void sugerirNombre(final Song s) {
        if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        // Armar artista y canción lo mejor posible
        String artista = (s.artist != null && !s.artist.equalsIgnoreCase("Desconocido")) ? s.artist.trim() : "";
        String cancion = (s.title != null) ? s.title.trim() : "";
        String base = nombreParaPartir(s);
        if (artista.length() == 0 && base.indexOf("-") >= 0) {
            String[] pz = base.split("\\s*-\\s*");
            if (pz.length >= 2) { artista = pz[0].trim(); cancion = pz[pz.length - 1].trim(); }
        }
        if (cancion.length() == 0) cancion = base;
        buscarSugerenciasCon(s, artista, cancion);
    }
    private void buscarSugerenciasCon(final Song s, final String artista, final String cancion) {
        if (!hayInternet()) { Toast.makeText(this, "Necesitas internet para sugerir el nombre", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Buscando el nombre correcto…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                java.util.ArrayList<String> terms = new java.util.ArrayList<String>();
                if (artista.length() > 0 && cancion.length() > 0) terms.add(limpiarBusqueda(artista + " " + cancion));
                if (cancion.length() > 0) terms.add(limpiarBusqueda(cancion));
                if (artista.length() > 0) terms.add(limpiarBusqueda(artista));
                final java.util.ArrayList<String[]> items = new java.util.ArrayList<String[]>();
                for (int ti = 0; ti < terms.size() && items.size() < 12; ti++) {
                    String term = terms.get(ti);
                    if (term == null || term.length() == 0) continue;
                    sugItunes(term, items);
                    sugDeezer(term, items);
                }
                sugMusicBrainz(artista.length() > 0 ? limpiarBusqueda(artista + " " + cancion) : limpiarBusqueda(cancion), items);
                java.util.HashSet<String> vistos = new java.util.HashSet<String>();
                java.util.ArrayList<String[]> unicos = new java.util.ArrayList<String[]>();
                for (int i = 0; i < items.size(); i++) {
                    String clave = (items.get(i)[0] + "|" + items.get(i)[1]).toLowerCase();
                    if (vistos.add(clave)) unicos.add(items.get(i));
                }
                items.clear(); items.addAll(unicos);
                while (items.size() > 10) items.remove(items.size() - 1);
                final java.util.ArrayList<android.graphics.Bitmap> thumbs = new java.util.ArrayList<android.graphics.Bitmap>();
                for (int i = 0; i < items.size(); i++) {
                    byte[] b = bajarUrl(items.get(i)[2]);
                    thumbs.add(b != null ? decodeEscalado(b, 160) : null);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (items.isEmpty()) { pedirBusquedaManual(s, artista, cancion); return; }
                        mostrarSugerencias(s, items, thumbs);
                    }
                });
            }
        }).start();
    }
    // Si no encontró nada, dejar que el usuario escriba el artista y la canción
    private void pedirBusquedaManual(final Song s, String artistaIni, String cancionIni) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16*d); box.setPadding(pad, pad, pad, pad);
        TextView msg = new TextView(this);
        msg.setText("No se encontró nada. Escribe el artista y la canción para buscar:");
        msg.setTextColor(0xFFB8B8C6); msg.setTextSize(13);
        final android.widget.EditText etA = new android.widget.EditText(this);
        etA.setHint("Artista"); etA.setText(artistaIni != null ? artistaIni : ""); etA.setSingleLine(true);
        final android.widget.EditText etC = new android.widget.EditText(this);
        etC.setHint("Canción"); etC.setText(cancionIni != null ? cancionIni : ""); etC.setSingleLine(true);
        box.addView(msg); box.addView(etA); box.addView(etC);
        new AlertDialog.Builder(this).setTitle("Buscar nombre correcto").setView(box)
            .setPositiveButton("Buscar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dg, int w) {
                    buscarSugerenciasCon(s, etA.getText().toString().trim(), etC.getText().toString().trim());
                }
            })
            .setNegativeButton("Cancelar", null).show();
    }
    private void mostrarSugerencias(final Song s, final java.util.ArrayList<String[]> items, final java.util.ArrayList<android.graphics.Bitmap> thumbs) {
        final float d = getResources().getDisplayMetrics().density;
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return null; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup p) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int pd = (int) (8 * d); row.setPadding(pd, pd, pd, pd);
                ImageView iv = new ImageView(MainActivity.this);
                int sz = (int) (56 * d);
                iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(sz, sz));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (thumbs.get(i) != null) iv.setImageBitmap(thumbs.get(i)); else iv.setImageDrawable(new ColorDrawable(0xFF262633));
                android.widget.LinearLayout col = new android.widget.LinearLayout(MainActivity.this);
                col.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = (int) (12 * d); col.setLayoutParams(clp);
                TextView t1 = new TextView(MainActivity.this);
                t1.setText(items.get(i)[0]); t1.setTextColor(0xFFF4F4F8); t1.setTextSize(16); t1.setTypeface(null, android.graphics.Typeface.BOLD); t1.setSingleLine(true);
                TextView t2 = new TextView(MainActivity.this);
                t2.setText(items.get(i)[1]); t2.setTextColor(0xFF8B8B9A); t2.setTextSize(13); t2.setSingleLine(true);
                col.addView(t1); col.addView(t2);
                row.addView(iv); row.addView(col);
                return row;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Toca el nombre correcto").setView(lv).setNegativeButton("Cancelar", null).create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                try { dlg.dismiss(); } catch (Exception e) {}
                final String tit = items.get(pos)[0], art = items.get(pos)[1], url = items.get(pos)[2];
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(tit + (art.length() > 0 ? " - " + art : ""))
                    .setItems(new String[]{ "Solo corregir el nombre", "Nombre + carátula" }, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dd, int w) {
                            final boolean conCaratula = (w == 1);
                            s.title = tit; if (art.length() > 0) s.artist = art;
                            txtTitle.setText(s.title);
                            txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
                            if (eqNombre != null) eqNombre.setInfo(s.artist, s.title);
                            Toast.makeText(MainActivity.this, conCaratula ? "Corrigiendo y bajando carátula…" : "Guardando el nombre…", Toast.LENGTH_SHORT).show();
                            new Thread(new Runnable() {
                                public void run() {
                                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                                    byte[] img = conCaratula ? bajarUrl(url) : null;
                                    if (img != null) { synchronized (artCache) { artCache.put(s.path, img); } }
                                    final byte[] paraEmbeber = (conCaratula && img != null) ? img : artActual(s);
                                    if (optEmbed) embedArt(s, paraEmbeber);
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            refrescarSiActual(s);
                                            try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                                            Toast.makeText(MainActivity.this, conCaratula ? "Nombre y carátula corregidos" : "Nombre corregido", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            }).start();
                        }
                    }).show();
            }
        });
        dlg.show();
    }

    private void aplicarNombre(Song s, String tit, String art) {
        if (tit != null && tit.length() > 0) s.title = tit;
        if (art != null && art.length() > 0) s.artist = art;
    }
    private void guardarNombre(final Song s, final String tit, final String art) {
        Toast.makeText(this, "Guardando…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                aplicarNombre(s, tit, art);
                final boolean ok = optEmbed ? embedArt(s, artActual(s)) : true;
                runOnUiThread(new Runnable() {
                    public void run() {
                        txtTitle.setText(s.title);
                        txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
                        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                        Toast.makeText(MainActivity.this, ok ? "Nombre guardado" : "No se pudo escribir el MP3", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }
    // Trae la FOTO DEL ARTISTA desde Deezer (cuando no hay portada del álbum)
    private void urlsFotoArtista(String artista, java.util.ArrayList<String> out) {
        try {
            String q = URLEncoder.encode(artista, "UTF-8");
            String json = httpGet("http://api.deezer.com/search/artist?limit=6&q=" + q);
            if (json == null) json = httpGet("https://api.deezer.com/search/artist?limit=6&q=" + q);
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("data");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.getJSONObject(i);
                String pic = a.optString("picture_xl", a.optString("picture_big", a.optString("picture_medium", "")));
                if (pic.length() > 0 && !out.contains(pic)) out.add(pic);
            }
        } catch (Exception e) {}
    }
    // Carátulas desde MusicBrainz + Cover Art Archive (capa extra)
    private void urlsMusicBrainz(String term, java.util.ArrayList<String> out) {
        try {
            String q = URLEncoder.encode(term, "UTF-8");
            String json = httpGet("https://musicbrainz.org/ws/2/release/?query=" + q + "&fmt=json&limit=5");
            if (json == null) return;
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("releases");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                String mbid = arr.getJSONObject(i).optString("id", "");
                if (mbid.length() > 0) {
                    String u = "https://coverartarchive.org/release/" + mbid + "/front-500";
                    if (!out.contains(u)) out.add(u);
                }
            }
        } catch (Exception e) {}
    }
    private void buscarCaratulas(final Song s, final String titIn, final String artIn) {
        Toast.makeText(this, "Buscando carátulas…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                // Derivar artista y canción (si el nombre viene junto con guión: "Artista - Canción")
                String art = artIn.trim(), tit = titIn.trim();
                if (art.length() == 0 && tit.indexOf("-") >= 0) {
                    String[] pz = tit.split("\\s*-\\s*");
                    if (pz.length >= 2) { art = pz[0].trim(); tit = pz[pz.length - 1].trim(); }
                }
                String artL = limpiarBusqueda(art), titL = limpiarBusqueda(tit);
                final java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                // Capa 1: artista + canción (portada del álbum, lo más exacto)
                if (artL.length() > 0 && titL.length() > 0) { urlsDeItunes(artL + " " + titL, urls); urlsDeDeezer(artL + " " + titL, urls); }
                // Capa 2: solo la canción
                if (titL.length() > 0) { urlsDeItunes(titL, urls); urlsDeDeezer(titL, urls); }
                // Capa 3: solo el artista (otros álbumes del artista)
                if (artL.length() > 0) { urlsDeItunes(artL, urls); urlsDeDeezer(artL, urls); }
                // Capa 4: MusicBrainz (más carátulas)
                if (artL.length() > 0 && titL.length() > 0) urlsMusicBrainz(artL + " " + titL, urls);
                else if (titL.length() > 0) urlsMusicBrainz(titL, urls);
                // Capa 5: FOTO del artista (último recurso)
                if (artL.length() > 0) urlsFotoArtista(artL, urls);
                while (urls.size() > 12) urls.remove(urls.size() - 1);
                final java.util.ArrayList<byte[]> bytesList = new java.util.ArrayList<byte[]>();
                final java.util.ArrayList<android.graphics.Bitmap> thumbs = new java.util.ArrayList<android.graphics.Bitmap>();
                for (int i = 0; i < urls.size(); i++) {
                    byte[] b = bajarUrl(urls.get(i));
                    if (b == null) continue;
                    android.graphics.Bitmap th = decodeEscalado(b, 240);
                    if (th == null) continue;
                    bytesList.add(b); thumbs.add(th);
                }
                final String titF = titIn, artF = artIn;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (thumbs.isEmpty()) { Toast.makeText(MainActivity.this, "No se encontraron carátulas para esa búsqueda", Toast.LENGTH_SHORT).show(); return; }
                        mostrarSelectorVisual(s, bytesList, thumbs, titF, artF);
                    }
                });
            }
        }).start();
    }
    // Muestra las carátulas en cuadrícula para elegir la correcta con el dedo
    private void mostrarSelectorVisual(final Song s, final java.util.ArrayList<byte[]> bytesList, final java.util.ArrayList<android.graphics.Bitmap> thumbs, final String tit, final String art) {
        float d = getResources().getDisplayMetrics().density;
        final android.widget.GridView g = new android.widget.GridView(this);
        g.setNumColumns(3);
        int pad = (int) (8 * d);
        g.setPadding(pad, pad, pad, pad);
        g.setHorizontalSpacing(pad); g.setVerticalSpacing(pad);
        final int sz = (int) (94 * d);
        g.setAdapter(new BaseAdapter() {
            public int getCount() { return thumbs.size(); }
            public Object getItem(int i) { return null; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup p) {
                ImageView iv = (cv instanceof ImageView) ? (ImageView) cv : new ImageView(MainActivity.this);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(sz, sz));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(thumbs.get(i));
                return iv;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Toca la carátula correcta").setView(g).setNegativeButton("Cancelar", null).create();
        g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                try { dlg.dismiss(); } catch (Exception e) {}
                byte[] img = bytesList.get(pos);
                aplicarNombre(s, tit, art);
                artCache.put(s.path, img);
                if (optEmbed) embedArt(s, img);
                txtTitle.setText(s.title);
                txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
                try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                refrescarSiActual(s);
                Toast.makeText(MainActivity.this, "Carátula guardada", Toast.LENGTH_SHORT).show();
            }
        });
        dlg.show();
    }
    private void aplicarCaratula(final Song s, final String url, final String tit, final String art) {
        Toast.makeText(this, "Bajando carátula…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final byte[] img = httpGetBytes(url);
                if (img != null) {
                    aplicarNombre(s, tit, art);
                    artCache.put(s.path, img);
                    if (optEmbed) embedArt(s, img);
                    runOnUiThread(new Runnable() { public void run() {
                        txtTitle.setText(s.title);
                        txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
                        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
                        refrescarSiActual(s);
                        Toast.makeText(MainActivity.this, "Carátula y nombre guardados", Toast.LENGTH_SHORT).show();
                    } });
                } else runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, "No se pudo bajar", Toast.LENGTH_SHORT).show(); } });
            }
        }).start();
    }

    // Descargar carátula de UNA canción (al sonar)
    private void descargarArtDe(final Song s) {
        new Thread(new Runnable() {
            public void run() {
                if (tieneArtEmbebida(s) || artCache.containsKey(s.path)) return;
                byte[] img = descargarArt(s);
                if (img != null) {
                    artCache.put(s.path, img);
                    if (optEmbed) embedArt(s, img);
                    runOnUiThread(new Runnable(){ public void run(){ refrescarSiActual(s); }});
                }
            }
        }).start();
    }

    // Opciones de una lista (renombrar / borrar)
    private void opcionesLista(final int pos) {
        if (pos < 0 || pos >= nombresListas.size()) return;
        final String n = nombresListas.get(pos);
        final boolean esFav = n.equals("Favoritas");
        final String[] ops = esFav
            ? new String[]{ "Ordenar A-Z", "Ordenar por artista", "Vaciar lista" }
            : new String[]{ "Renombrar", "Ordenar A-Z", "Ordenar por artista", "Subir", "Bajar", "Vaciar lista", "Borrar" };
        new AlertDialog.Builder(this).setTitle(n).setItems(ops, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                if (esFav) {
                    if (w == 0) ordenarLista(n, 0);
                    else if (w == 1) ordenarLista(n, 1);
                    else vaciarLista(n);
                } else {
                    switch (w) {
                        case 0: renombrarLista(n); break;
                        case 1: ordenarLista(n, 0); break;
                        case 2: ordenarLista(n, 1); break;
                        case 3: moverLista(pos, -1); break;
                        case 4: moverLista(pos, 1); break;
                        case 5: vaciarLista(n); break;
                        case 6: borrarLista(n); break;
                    }
                }
            }
        }).show();
    }
    // Mover una lista arriba/abajo (dir = -1 sube, +1 baja). Favoritas queda fija en el tope.
    private void moverLista(int pos, int dir) {
        int j = pos + dir;
        if (pos <= 0 || j <= 0 || pos >= nombresListas.size() || j >= nombresListas.size()) return;
        String tmp = nombresListas.get(pos); nombresListas.set(pos, nombresListas.get(j)); nombresListas.set(j, tmp);
        guardarOrdenListas();
        adapter.notifyDataSetChanged();
    }
    private void guardarOrdenListas() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (int i = 0; i < nombresListas.size(); i++) { if (!nombresListas.get(i).equals("Favoritas")) a.put(nombresListas.get(i)); }
        prefs.edit().putString("ordenListas", a.toString()).apply();
    }
    // Ordena las canciones dentro de la lista (criterio 0 = título, 1 = artista)
    private void ordenarLista(final String n, final int criterio) {
        try {
            org.json.JSONArray a = listas.getJSONArray(n);
            java.util.ArrayList<String> rutas = new java.util.ArrayList<String>();
            for (int i = 0; i < a.length(); i++) rutas.add(a.optString(i));
            java.util.Collections.sort(rutas, new java.util.Comparator<String>() {
                public int compare(String p1, String p2) {
                    return claveOrden(songPorRuta(p1), criterio).compareToIgnoreCase(claveOrden(songPorRuta(p2), criterio));
                }
            });
            org.json.JSONArray na = new org.json.JSONArray();
            for (int i = 0; i < rutas.size(); i++) na.put(rutas.get(i));
            listas.put(n, na); guardarListas();
            if (modo == 1 && carpetaAbierta != null && carpetaAbierta.esLista && n.equals(carpetaAbierta.name)) {
                ArrayList<Song> arr = new ArrayList<Song>();
                for (int i = 0; i < na.length(); i++) { Song s = songPorRuta(na.optString(i)); if (s != null) arr.add(s); }
                carpetaAbierta.songs = arr; cancionesCarpeta = arr; adapter.notifyDataSetChanged();
            }
            Toast.makeText(this, "Lista ordenada", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {}
    }
    private String claveOrden(Song s, int criterio) {
        if (s == null) return "";
        if (criterio == 1) return (s.artist != null ? s.artist : "") + " " + (s.title != null ? s.title : "");
        return (s.title != null && s.title.length() > 0) ? s.title : nombreDe(s);
    }
    private void vaciarLista(final String n) {
        new AlertDialog.Builder(this).setMessage("¿Quitar TODAS las canciones de \"" + n + "\"?\n(La lista no se borra, queda vacía.)")
            .setPositiveButton("Vaciar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try { listas.put(n, new org.json.JSONArray()); guardarListas(); } catch (Exception e) {}
                    if (modo == 1 && carpetaAbierta != null && carpetaAbierta.esLista && n.equals(carpetaAbierta.name)) {
                        carpetaAbierta.songs = new ArrayList<Song>(); cancionesCarpeta = carpetaAbierta.songs;
                    }
                    calcularPortadasListas(); adapter.notifyDataSetChanged(); actualizarHeaderLista();
                }
            }).setNegativeButton("Cancelar", null).show();
    }
    private void renombrarLista(final String n) {
        final EditText et = new EditText(this); et.setText(n);
        new AlertDialog.Builder(this).setTitle("Nuevo nombre").setView(et).setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                String nn = et.getText().toString().trim();
                if (nn.length() > 0 && !nn.equals(n)) {
                    try { listas.put(nn, listas.getJSONArray(n)); listas.remove(n); guardarListas(); } catch (Exception e) {}
                    // conservar la posición en el orden guardado
                    try { String s = prefs.getString("ordenListas", ""); if (s.length() > 0) { org.json.JSONArray a = new org.json.JSONArray(s); for (int i = 0; i < a.length(); i++) { if (a.optString(i).equals(n)) a.put(i, nn); } prefs.edit().putString("ordenListas", a.toString()).apply(); } } catch (Exception e) {}
                    construirNombresListas(); calcularPortadasListas(); adapter.notifyDataSetChanged(); actualizarHeaderLista();
                }
            }
        }).setNegativeButton("Cancelar", null).show();
    }
    private void borrarLista(final String n) {
        new AlertDialog.Builder(this).setMessage("¿Borrar la lista \"" + n + "\"?").setPositiveButton("Borrar", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { listas.remove(n); guardarListas(); construirNombresListas(); calcularPortadasListas(); adapter.notifyDataSetChanged(); actualizarHeaderLista(); }
        }).setNegativeButton("Cancelar", null).show();
    }

    private android.graphics.drawable.GradientDrawable circulo(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
    // Toma color de la izquierda y de la derecha de la carátula (degradado horizontal, estilo Poweramp)
    private int[] coloresDe(Bitmap bmp) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(bmp, 4, 4, true);
            long r1 = 0, g1 = 0, b1 = 0, r2 = 0, g2 = 0, b2 = 0; int n1 = 0, n2 = 0;
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                int px = s.getPixel(x, y); int r = (px >> 16) & 255, g = (px >> 8) & 255, b = px & 255;
                if (x < 2) { r1 += r; g1 += g; b1 += b; n1++; } else { r2 += r; g2 += g; b2 += b; n2++; }
            }
            if (n1 == 0) n1 = 1; if (n2 == 0) n2 = 1;
            int c1 = oscurecer((int) (r1 / n1), (int) (g1 / n1), (int) (b1 / n1), 0.85f);
            int c2 = oscurecer((int) (r2 / n2), (int) (g2 / n2), (int) (b2 / n2), 0.85f);
            return new int[]{ c1, c2 };
        } catch (Exception e) { return new int[]{ 0xFF1a1a26, 0xFF06060a }; }
    }
    private int oscurecer(int r, int g, int b, float f) {
        return 0xFF000000 | ((int) (r * f) << 16) | ((int) (g * f) << 8) | (int) (b * f);
    }
    private Bitmap decodeEscalado(byte[] data, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while ((o.outWidth / s) > max || (o.outHeight / s) > max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;  // la mitad de memoria (las portadas no necesitan transparencia)
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable e) {
            try {  // reintento si falla por memoria
                BitmapFactory.Options o3 = new BitmapFactory.Options();
                o3.inSampleSize = 4; o3.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o3);
            } catch (Throwable e2) { return null; }
        }
    }
    // Desenfoque suave estilo Poweramp: reduce la carátula a un tamaño pequeño y le aplica
    // un box blur de 2 pasadas (horizontal + vertical). Es liviano (imagen chica) y se ve cremoso.
    private Bitmap desenfocar(Bitmap src, int size, int radio) {
        try {
            Bitmap sm = Bitmap.createScaledBitmap(src, size, size, true);
            int w = sm.getWidth(), h = sm.getHeight();
            int[] a = new int[w * h]; sm.getPixels(a, 0, w, 0, 0, w, h);
            int[] b = new int[w * h];
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < h; y++) { int off = y * w;
                    for (int x = 0; x < w; x++) { int r = 0, g = 0, bl = 0, cnt = 0;
                        for (int k = -radio; k <= radio; k++) { int xx = x + k; if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1; int p = a[off + xx]; r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; bl += p & 0xff; cnt++; }
                        b[off + x] = 0xff000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (bl / cnt); } }
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) { int r = 0, g = 0, bl = 0, cnt = 0;
                        for (int k = -radio; k <= radio; k++) { int yy = y + k; if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1; int p = b[yy * w + x]; r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; bl += p & 0xff; cnt++; }
                        a[y * w + x] = 0xff000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (bl / cnt); } }
            }
            return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) { return null; }
    }
    private int[] gradienteDe(Song s) {
        int h = ((s.title == null ? "" : s.title) + (s.artist == null ? "" : s.artist)).hashCode();
        int[][] pal = {
            { 0xFF1E6E5A, 0xFF06121A }, { 0xFF6E1E5A, 0xFF190712 }, { 0xFF1E3A6E, 0xFF06101A },
            { 0xFF6E4A1E, 0xFF191207 }, { 0xFF3A1E6E, 0xFF0C0719 }, { 0xFF1E6E3A, 0xFF06190C }
        };
        return pal[Math.abs(h) % pal.length];
    }
    private void pintarPlay(boolean playing) {
        btnPlay.setImageResource(playing ? R.drawable.ic_jfv_pause : R.drawable.ic_jfv_play);
        if (playing) tomarWake(); else liberarWake();
        aplicarEfectos();
    }
    private void pintarShuffle() {
        btnShuffle.setTextColor(shuffle ? accent : 0xFF8B8B9A);
    }
    private void pintarRepeat() {
        btnRepeat.setText(repeat == 0 ? "Repetir" : repeat == 1 ? "Repetir: Todo" : "Repetir: Una");
        btnRepeat.setTextColor(repeat == 0 ? 0xFF8B8B9A : accent);
    }

    private String fmt(int ms) {
        int s = ms / 1000; int m = s / 60; int x = s % 60;
        return m + ":" + (x < 10 ? "0" : "") + x;
    }

    private class SongAdapter extends BaseAdapter {
        public int getCount() {
            if (modo == 0) { if (tab == 0) return carpetas.size(); if (tab == 1) return nombresListas.size(); return videos.size(); }
            return cancionesCarpeta.size();
        }
        public Object getItem(int i) { return null; }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int i) { return (modo == 1 || (modo == 0 && tab == 2)) ? 1 : 0; }
        public View getView(int i, View convertView, ViewGroup parent) {
            int tipo = getItemViewType(i);
            View v = convertView;
            if (v == null || ((Integer) (v.getTag() == null ? -1 : v.getTag())) != tipo) {
                v = getLayoutInflater().inflate(tipo == 0 ? R.layout.row_folder : R.layout.row_song, parent, false);
                v.setTag(Integer.valueOf(tipo));
            }
            if (tipo == 0) {
                TextView nm = (TextView) v.findViewById(R.id.folName);
                TextView ct = (TextView) v.findViewById(R.id.folCount);
                ImageView th = (ImageView) v.findViewById(R.id.folThumb);
                android.graphics.Bitmap bmp;
                if (tab == 0) {
                    Carpeta c = carpetas.get(i);
                    nm.setText(c.name); ct.setText(c.songs.size() + " canciones");
                    synchronized (portadas) { bmp = portadas.get("c:" + c.name); }
                } else {
                    String n = nombresListas.get(i); int cnt = 0;
                    try { cnt = listas.getJSONArray(n).length(); } catch (Exception e) {}
                    nm.setText((n.equals("Favoritas") ? "★ " : "") + n); ct.setText(cnt + " canciones");
                    synchronized (portadas) { bmp = portadas.get("l:" + n); }
                }
                if (bmp != null) th.setImageBitmap(bmp);
                else th.setImageDrawable(new ColorDrawable(0xFF262633));
            } else {
                TextView t = (TextView) v.findViewById(R.id.rowTitle);
                TextView a = (TextView) v.findViewById(R.id.rowArtist);
                TextView d = (TextView) v.findViewById(R.id.rowDur);
                if (modo == 0 && tab == 2) {
                    Song vid = videos.get(i);
                    t.setText(vid.title); a.setText("Video"); d.setText("");
                    t.setTextColor(0xFFF4F4F8);
                } else {
                    Song s = cancionesCarpeta.get(i);
                    t.setText(s.title); a.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "");
                    d.setText(s.dur > 0 ? fmt(s.dur) : "");
                    t.setTextColor(rutaActualCache != null && rutaActualCache.equals(s.path) ? accent : 0xFFF4F4F8);
                }
            }
            return v;
        }
    }

    // Portada de una lista de canciones (primera con carátula)
    private android.graphics.Bitmap portadaDe(ArrayList<Song> lista) {
        int lim = Math.min(lista.size(), 8);
        for (int i = 0; i < lim; i++) {
            Song s = lista.get(i);
            byte[] d = null;
            synchronized (artCache) { if (artCache.containsKey(s.path)) d = artCache.get(s.path); }
            if (d == null) {
                try { MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(s.path); d = r.getEmbeddedPicture(); try { r.release(); } catch (Exception e) {} } catch (Exception e) {}
            }
            if (d != null) {
                android.graphics.Bitmap b = decodeEscalado(d, 160);  // miniatura chica y liviana (RGB_565)
                if (b != null) return b;
            }
        }
        return null;
    }
    private void calcularPortadasCarpetas() {
        new Thread(new Runnable() {
            public void run() {
                for (Carpeta c : carpetas) {
                    boolean ya; synchronized (portadas) { ya = portadas.containsKey("c:" + c.name); }
                    if (ya) continue;
                    android.graphics.Bitmap b = portadaDe(c.songs);
                    if (b != null) synchronized (portadas) { portadas.put("c:" + c.name, b); }
                }
                runOnUiThread(new Runnable() { public void run() { adapter.notifyDataSetChanged(); } });
            }
        }).start();
    }
    private void calcularPortadasListas() {
        new Thread(new Runnable() {
            public void run() {
                for (String n : nombresListas) {
                    boolean ya; synchronized (portadas) { ya = portadas.containsKey("l:" + n); }
                    if (ya) continue;
                    ArrayList<Song> arr = new ArrayList<Song>();
                    try { org.json.JSONArray a = listas.getJSONArray(n); for (int k = 0; k < a.length() && k < 8; k++) { Song s = songPorRuta(a.optString(k)); if (s != null) arr.add(s); } } catch (Exception e) {}
                    android.graphics.Bitmap b = portadaDe(arr);
                    if (b != null) synchronized (portadas) { portadas.put("l:" + n, b); }
                }
                runOnUiThread(new Runnable() { public void run() { adapter.notifyDataSetChanged(); } });
            }
        }).start();
    }

    private int videoPosGuardada = 0;
    private boolean videoEstabaReproduciendo = false;
    protected void onPause() {
        super.onPause();
        // Si hay un video reproduciéndose (ej. al poner reversa), guardar dónde va y pausarlo (para no reiniciarlo)
        try {
            View pv = findViewById(R.id.paneVideo);
            if (pv != null && pv.getVisibility() == View.VISIBLE && videoView != null && videoView.isPlaying()) {
                videoPosGuardada = videoView.getCurrentPosition();
                videoEstabaReproduciendo = true;
                videoView.pause();
            }
        } catch (Exception e) {}
    }
    protected void onResume() {
        super.onResume();
        activo = this;
        // Registrar teclas del volante SIN pedir prioridad de audio (para no quitar el mute al volver de la cámara)
        try { if (mbCn != null) am.registerMediaButtonEventReceiver(mbCn); } catch (Exception e) {}
        // Reanudar el video donde iba (al quitar la reversa), sin reiniciarlo
        try {
            View pv = findViewById(R.id.paneVideo);
            if (videoEstabaReproduciendo && pv != null && pv.getVisibility() == View.VISIBLE && videoView != null) {
                if (videoPosGuardada > 0) videoView.seekTo(videoPosGuardada);
                videoView.start();
            }
            videoEstabaReproduciendo = false;
        } catch (Exception e) {}
    }
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(actualizador);
        try { if (usbReceiver != null) unregisterReceiver(usbReceiver); } catch (Exception e) {}
        try { if (netReceiver != null) unregisterReceiver(netReceiver); } catch (Exception e) {}
        try { if (volObserver != null) getContentResolver().unregisterContentObserver(volObserver); } catch (Exception e) {}
        try { if (visualizer != null) { try { visualizer.setEnabled(false); } catch (Exception e) {} visualizer.release(); } } catch (Exception e) {}
        try { if (vizBg != null) vizBg.parar(); } catch (Exception e) {}
        try { if (particles != null) particles.parar(); } catch (Exception e) {}
        try { if (mbCn != null) am.unregisterMediaButtonEventReceiver(mbCn); } catch (Exception e) {}
        try { if (servidor != null) servidor.detener(); } catch (Exception e) {}
        soltarCandadosWifi();
        try { if (videoView != null) videoView.stopPlayback(); } catch (Exception e) {}
        if (activo == this) activo = null;
        liberarWake();
        try { if (eq != null) eq.release(); } catch (Exception e) {}
        try { if (mp != null) mp.release(); } catch (Exception e) {}
        eq = null; mp = null;
    }

    // El sistema avisa que anda bajo de memoria: soltamos las carátulas guardadas para no cerrarnos
    public void onTrimMemory(int level) {
        try { super.onTrimMemory(level); } catch (Exception e) {}
        // Solo en emergencia REAL (app oculta y por cerrarse) soltamos las carátulas grandes.
        // NO borramos las miniaturas de carpetas para que no desaparezcan al bajar una carátula.
        if (level >= TRIM_MEMORY_COMPLETE) {
            try { artCache.clear(); } catch (Exception e) {}
        }
    }
    public void onLowMemory() {
        try { super.onLowMemory(); } catch (Exception e) {}
        try { artCache.clear(); } catch (Exception e) {}
        synchronized (portadas) { try { portadas.clear(); } catch (Exception e) {} }
    }
}
