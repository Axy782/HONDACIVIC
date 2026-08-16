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

    private boolean shuffle = false;
    private int repeat = 0;
    private boolean prepared = false;
    private boolean noAutoStart = false;

    private ImageView imgArt;
    private TextView txtTitle, txtArtist, txtCur, txtDur, txtCount;
    private SeekBar seek, vol;
    private ImageButton btnPlay, btnPrev, btnNext;
    private Button btnShuffle, btnRepeat, btnEq;
    private ListView list;
    private SongAdapter adapter;
    private View paneReproduciendo, paneLista;
    private Button btnAbrirLista, btnVolver;
    // Ajustes
    private View paneAjustes, paneExplorar;
    private boolean optAuto = false, optEmbed = true;
    private String carpetaVinculada = null;
    private final java.util.HashMap<String, byte[]> artCache = new java.util.HashMap<String, byte[]>();
    private ListView listExplorar;
    private android.widget.ArrayAdapter<String> expAdapter;
    private final ArrayList<String> expItems = new ArrayList<String>();
    private final ArrayList<File> expDirs = new ArrayList<File>();
    private File expActual = null;
    // Listas de reproducción
    private org.json.JSONObject listas = new org.json.JSONObject();  // nombre -> [rutas]
    private int tab = 0;   // 0 = carpetas, 1 = mis listas
    private final ArrayList<String> nombresListas = new ArrayList<String>();
    // Opciones extra
    private String optCalidad = "alta", optTema = "ambar", optOrden = "nombre";
    private boolean optResume = true, optAutoplay = false, optPausaUsb = false, optPantalla = false, optVolArranque = true;
    private int accent = 0xFFFFB020;
    private android.os.PowerManager.WakeLock wakeCpu = null;
    private android.content.BroadcastReceiver usbReceiver = null;
    private android.content.BroadcastReceiver netReceiver = null;
    private android.database.ContentObserver volObserver = null;
    private boolean descargaEnCurso = false;

    private final Handler handler = new Handler();
    private SharedPreferences prefs;

    private short[] eqLevels = null;
    private boolean eqEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("sonido", MODE_PRIVATE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        imgArt = (ImageView) findViewById(R.id.art);
        txtTitle = (TextView) findViewById(R.id.title);
        txtArtist = (TextView) findViewById(R.id.artist);
        txtCur = (TextView) findViewById(R.id.tCur);
        txtDur = (TextView) findViewById(R.id.tDur);
        txtCount = (TextView) findViewById(R.id.count);
        seek = (SeekBar) findViewById(R.id.seek);
        vol = (SeekBar) findViewById(R.id.vol);
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
        carpetaVinculada = prefs.getString("carpetaVinc", null);
        optCalidad = prefs.getString("calidad", "alta");
        optTema = prefs.getString("tema", "ambar");
        optOrden = prefs.getString("orden", "nombre");
        optResume = prefs.getBoolean("resume", true);
        optAutoplay = prefs.getBoolean("autoplay", false);
        optPausaUsb = prefs.getBoolean("pausaUsb", false);
        optPantalla = prefs.getBoolean("pantalla", false);
        optVolArranque = prefs.getBoolean("volArranque", true);
        aplicarTema();

        findViewById(R.id.btnAjustes).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarPane(2); }});
        findViewById(R.id.btnCerrarAjustes).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarPane(0); }});
        final CheckBox chkAuto = (CheckBox) findViewById(R.id.chkAuto);
        final CheckBox chkEmbed = (CheckBox) findViewById(R.id.chkEmbed);
        chkAuto.setChecked(optAuto); chkEmbed.setChecked(optEmbed);
        chkAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optAuto=c; prefs.edit().putBoolean("optAuto",c).apply(); }});
        chkEmbed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optEmbed=c; prefs.edit().putBoolean("optEmbed",c).apply(); }});
        findViewById(R.id.btnVincular).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ abrirExplorador(); }});
        findViewById(R.id.btnDesvincular).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ carpetaVinculada=null; prefs.edit().remove("carpetaVinc").apply(); pintarAjustes(); escanearMusica(); }});
        findViewById(R.id.btnDescargarArt).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ descargarFaltantes(); }});
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
        findViewById(R.id.btnFav).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toggleFav(); }});
        findViewById(R.id.btnMasLista).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ mostrarMasLista(); }});
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
            long ultimoTap = 0; int toques = 0;
            public boolean onTouch(View v, MotionEvent e) {
                gestos.onTouchEvent(e);
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    long ahora = System.currentTimeMillis();
                    if (ahora - ultimoTap < 600) toques++; else toques = 1;
                    ultimoTap = ahora;
                    if (toques >= 5) { toques = 0; cambiarCaratula(); }
                }
                return true;
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener(){
            public boolean onItemLongClick(AdapterView<?> p, View v, int position, long id){
                if (modo == 1 && carpetaAbierta != null && carpetaAbierta.esLista) { quitarDeListaActual(position); return true; }
                if (modo == 0 && tab == 1) { opcionesLista(position); return true; }
                return false;
            }
        });

        // ---- Ajustes extra ----
        final CheckBox chkResume = (CheckBox) findViewById(R.id.chkResume);
        final CheckBox chkAutoplay = (CheckBox) findViewById(R.id.chkAutoplay);
        final CheckBox chkPausaUsb = (CheckBox) findViewById(R.id.chkPausaUsb);
        final CheckBox chkPantalla = (CheckBox) findViewById(R.id.chkPantalla);
        final CheckBox chkVolArranque = (CheckBox) findViewById(R.id.chkVolArranque);
        chkResume.setChecked(optResume); chkAutoplay.setChecked(optAutoplay); chkPausaUsb.setChecked(optPausaUsb); chkPantalla.setChecked(optPantalla);
        chkVolArranque.setChecked(optVolArranque);
        chkVolArranque.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optVolArranque=c; prefs.edit().putBoolean("volArranque",c).apply(); }});
        chkResume.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optResume=c; prefs.edit().putBoolean("resume",c).apply(); }});
        chkAutoplay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){ public void onCheckedChanged(CompoundButton b, boolean c){ optAutoplay=c; prefs.edit().putBoolean("autoplay",c).apply(); }});
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
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int position, long idd) {
                if (modo == 0) { if (tab == 0) abrirCarpeta(position); else abrirLista(position); }
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
        vol.setMax(maxv);
        if (optVolArranque) { am.setStreamVolume(AudioManager.STREAM_MUSIC, maxv / 2, 0); }
        vol.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        volObserver = new android.database.ContentObserver(new Handler()) {
            public void onChange(boolean self) {
                try { vol.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC)); } catch (Exception e) {}
            }
        };
        try { getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volObserver); } catch (Exception e) {}
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean fromUser){ if(fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0); }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });

        cargarEqGuardado();
        escanearMusica();
    }

    private static final int MAX_DEPTH = 9;

    private void escanearMusica() {
        txtCount.setText("Buscando música…");
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<Song> found = new ArrayList<Song>();
                Set<String> vistos = new HashSet<String>();
                for (File root : raices()) {
                    buscarAudio(root, found, vistos, 0);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        agruparEnCarpetas(found);
                        modo = 0; carpetaAbierta = null;
                        adapter.notifyDataSetChanged();
                        actualizarHeaderLista();
                        if (optAuto && hayInternet() && !carpetas.isEmpty()) descargarFaltantes();
                        if (optResume) {
                            String lp = prefs.getString("lastPath", null);
                            if (lp != null) {
                                Song s = songPorRuta(lp);
                                if (s != null) {
                                    for (Carpeta c : carpetas) {
                                        int ix = c.songs.indexOf(s);
                                        if (ix >= 0) {
                                            songs.clear(); songs.addAll(c.songs); construirOrden();
                                            posEnOrden = order.indexOf(ix); if (posEnOrden < 0) posEnOrden = 0;
                                            noAutoStart = !optAutoplay;
                                            cargarYReproducir();
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }).start();
    }

    // Carpetas donde buscar: si hay una vinculada, solo esa; si no, todo
    private ArrayList<File> raices() {
        if (carpetaVinculada != null) {
            File f = new File(carpetaVinculada);
            if (f.exists() && f.canRead()) { ArrayList<File> r = new ArrayList<File>(); r.add(f); return r; }
        }
        return raicesBase();
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

    // Lee título/artista/carátula reales del archivo que suena
    private void cargarMetadatosActual(Song s) {
        txtTitle.setText(s.title);
        txtArtist.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "Desconocido");
        Bitmap bmp = null;
        try {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            r.setDataSource(s.path);
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            byte[] pic = r.getEmbeddedPicture();
            try { r.release(); } catch (Exception e) {}
            if (t != null && t.trim().length() > 0) { s.title = t; txtTitle.setText(t); }
            if (a != null && a.trim().length() > 0) { s.artist = a; txtArtist.setText(a); }
            else txtArtist.setText("Desconocido");
            if (al != null && al.trim().length() > 0) s.album = al;
            if (pic != null) bmp = BitmapFactory.decodeByteArray(pic, 0, pic.length);
        } catch (Exception e) {}
        if (bmp == null && artCache.containsKey(s.path)) {
            try { byte[] c = artCache.get(s.path); bmp = BitmapFactory.decodeByteArray(c, 0, c.length); } catch (Exception e) {}
        }
        if (bmp != null) imgArt.setImageBitmap(bmp);
        else imgArt.setImageDrawable(new ColorDrawable(0xFF262633));
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
        if (t == 1) construirNombresListas();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        actualizarHeaderLista();
    }

    private void construirNombresListas() {
        nombresListas.clear();
        nombresListas.add("Favoritas");
        java.util.Iterator<String> it = listas.keys();
        while (it.hasNext()) { String k = it.next(); if (!k.equals("Favoritas")) nombresListas.add(k); }
    }
    private Song songPorRuta(String path) {
        for (Carpeta c : carpetas) for (Song s : c.songs) if (s.path.equals(path)) return s;
        return null;
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
        b.setTextColor(fav ? accent : 0xFF8B8B9A);
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
                    if (noAutoStart) { noAutoStart = false; pintarPlay(false); }
                    else { m.start(); pintarPlay(true); handler.post(actualizador); }
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
        else { mp.start(); pintarPlay(true); handler.post(actualizador); }
    }

    private void siguiente(boolean auto) {
        if (order.isEmpty()) return;
        if (repeat == 2 && auto) { if (mp != null) { mp.seekTo(0); mp.start(); } return; }
        if (posEnOrden < order.size() - 1) posEnOrden++;
        else { if (repeat == 1 || !auto) posEnOrden = 0; else { pintarPlay(false); return; } }
        cargarYReproducir();
    }

    private void anterior() {
        if (order.isEmpty()) return;
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        if (posEnOrden > 0) posEnOrden--; else posEnOrden = order.size() - 1;
        cargarYReproducir();
    }

    private final Runnable actualizador = new Runnable() {
        public void run() {
            if (mp != null && prepared && mp.isPlaying()) {
                seek.setProgress(mp.getCurrentPosition());
                txtCur.setText(fmt(mp.getCurrentPosition()));
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
                        eq.usePreset((short) (position - 1));
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
                final ArrayList<Song> todas = new ArrayList<Song>();
                for (Carpeta c : carpetas) todas.addAll(c.songs);
                int ok = 0;
                for (int i = 0; i < todas.size(); i++) {
                    final Song s = todas.get(i); final int idx = i + 1; final int tot = todas.size();
                    runOnUiThread(new Runnable(){ public void run(){ prog.setText("Buscando " + idx + "/" + tot + "…"); }});
                    if (tieneArtEmbebida(s) || artCache.containsKey(s.path)) continue;
                    byte[] img = descargarArt(s);
                    if (img != null) {
                        artCache.put(s.path, img);
                        if (optEmbed) embedArt(s, img);
                        ok++;
                        final Song ss = s;
                        runOnUiThread(new Runnable(){ public void run(){ refrescarSiActual(ss); }});
                    }
                    try { Thread.sleep(250); } catch (Exception e) {}
                }
                final int fok = ok;
                runOnUiThread(new Runnable(){ public void run(){ prog.setText(fok + " carátula(s) agregada(s)"); }});
                descargaEnCurso = false;
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
        try {
            String base = ((s.artist != null && s.artist.length() > 0 ? s.artist + " " : "")
                + (s.album != null && s.album.length() > 0 ? s.album : s.title)).trim();
            if (base.length() == 0) return null;
            String url = "https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + URLEncoder.encode(base, "UTF-8");
            String json = httpGet(url);
            if (json == null) return null;
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray arr = o.optJSONArray("results");
            if (arr == null || arr.length() == 0) return null;
            String art = arr.getJSONObject(0).optString("artworkUrl100", "");
            if (art.length() == 0) return null;
            String px = optCalidad.equals("maxima")?"1200x1200":optCalidad.equals("normal")?"300x300":"600x600";
            art = art.replace("100x100", px);
            return httpGetBytes(art);
        } catch (Exception e) { return null; }
    }
    private String httpGet(String u) {
        try {
            HttpURLConnection c = abrir(u);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }
    private byte[] httpGetBytes(String u) {
        try {
            HttpURLConnection c = abrir(u);
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
        c.setRequestProperty("User-Agent", "SonidoJFV");
        if (c instanceof HttpsURLConnection && Build.VERSION.SDK_INT < 21) {
            try { ((HttpsURLConnection) c).setSSLSocketFactory(new Tls12SocketFactory()); } catch (Exception e) {}
        }
        return c;
    }

    // ---------- Escribir carátula dentro del MP3 (ID3v2.3) ----------
    private boolean embedArt(Song s, byte[] img) {
        try {
            if (!s.path.toLowerCase(Locale.US).endsWith(".mp3")) return false;
            File f = new File(s.path);
            if (!f.canWrite()) { /* intentamos igual */ }
            byte[] buf = leerArchivo(f);
            int start = 0;
            if (buf.length > 10 && (buf[0] & 0xff) == 0x49 && (buf[1] & 0xff) == 0x44 && (buf[2] & 0xff) == 0x33) {
                start = 10 + ((buf[6] & 127) * 2097152 + (buf[7] & 127) * 16384 + (buf[8] & 127) * 128 + (buf[9] & 127));
            }
            byte[] tag = construirTag(s, img);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(tag);
            fos.write(buf, start, buf.length - start);
            fos.close();
            return true;
        } catch (Exception e) { return false; }
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
        body.write(frameApic(img));
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

    private void aplicarTema() {
        if (optTema.equals("azul")) accent = 0xFF3B82F6;
        else if (optTema.equals("rojo")) accent = 0xFFEF4444;
        else if (optTema.equals("verde")) accent = 0xFF22C55E;
        else accent = 0xFFFFB020;
        try { findViewById(R.id.btnPlay).setBackgroundColor(accent); } catch (Exception e) {}
        try { findViewById(R.id.btnMasLista).setBackgroundColor(accent); } catch (Exception e) {}
        try { ((Button) findViewById(R.id.btnEq)).setTextColor(accent); } catch (Exception e) {}
        try { pintarFav(); } catch (Exception e) {}
        try { adapter.notifyDataSetChanged(); } catch (Exception e) {}
        try { pintarSegCalidad(); pintarSegOrden(); } catch (Exception e) {}
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
                    if (optPausaUsb && mp != null && prepared && mp.isPlaying()) { mp.pause(); pintarPlay(false); }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_MEDIA_EJECT);
            f.addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(android.content.Intent.ACTION_MEDIA_REMOVED);
            f.addDataScheme("file");
            registerReceiver(usbReceiver, f);
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
    private void cambiarCaratula() {
        final Song s = cancionActual();
        if (s == null) { Toast.makeText(this, "Pon una canción primero", Toast.LENGTH_SHORT).show(); return; }
        final EditText et = new EditText(this);
        et.setText((s.artist != null && s.artist.length() > 0 ? s.artist + " " : "") + (s.title != null ? s.title : ""));
        new AlertDialog.Builder(this).setTitle("Buscar carátula").setView(et)
            .setPositiveButton("Buscar", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { buscarCaratulas(s, et.getText().toString().trim()); }
            }).setNegativeButton("Cancelar", null).show();
    }
    private void buscarCaratulas(final Song s, final String term) {
        if (term.length() == 0) return;
        Toast.makeText(this, "Buscando…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<String> labels = new ArrayList<String>();
                final ArrayList<String> urls = new ArrayList<String>();
                final boolean[] falloRed = { false };
                try {
                    String url = "https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + URLEncoder.encode(term, "UTF-8");
                    String json = httpGet(url);
                    if (json == null) { falloRed[0] = true; }
                    else {
                        org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("results");
                        if (arr != null) {
                            String px = optCalidad.equals("maxima") ? "1200x1200" : optCalidad.equals("normal") ? "300x300" : "600x600";
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject o = arr.getJSONObject(i);
                                String a = o.optString("artworkUrl100", "");
                                if (a.length() == 0) continue;
                                labels.add(o.optString("trackName", "?") + " - " + o.optString("artistName", ""));
                                urls.add(a.replace("100x100", px));
                            }
                        }
                    }
                } catch (Exception e) { falloRed[0] = true; }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (falloRed[0]) { Toast.makeText(MainActivity.this, "Sin conexión o el radio bloqueó internet", Toast.LENGTH_LONG).show(); return; }
                        if (labels.isEmpty()) { Toast.makeText(MainActivity.this, "Sin resultados para esa búsqueda", Toast.LENGTH_SHORT).show(); return; }
                        new AlertDialog.Builder(MainActivity.this).setTitle("Elige la carátula")
                            .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) { aplicarCaratula(s, urls.get(w)); }
                            }).show();
                    }
                });
            }
        }).start();
    }
    private void aplicarCaratula(final Song s, final String url) {
        Toast.makeText(this, "Bajando carátula…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final byte[] img = httpGetBytes(url);
                if (img != null) {
                    artCache.put(s.path, img);
                    if (optEmbed) embedArt(s, img);
                    runOnUiThread(new Runnable() { public void run() { refrescarSiActual(s); Toast.makeText(MainActivity.this, "Carátula actualizada", Toast.LENGTH_SHORT).show(); } });
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
    private void opcionesLista(int pos) {
        if (pos < 0 || pos >= nombresListas.size()) return;
        final String n = nombresListas.get(pos);
        if (n.equals("Favoritas")) return;
        new AlertDialog.Builder(this).setTitle(n).setItems(new String[]{ "Renombrar", "Borrar" }, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { if (w == 0) renombrarLista(n); else borrarLista(n); }
        }).show();
    }
    private void renombrarLista(final String n) {
        final EditText et = new EditText(this); et.setText(n);
        new AlertDialog.Builder(this).setTitle("Nuevo nombre").setView(et).setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                String nn = et.getText().toString().trim();
                if (nn.length() > 0 && !nn.equals(n)) {
                    try { listas.put(nn, listas.getJSONArray(n)); listas.remove(n); guardarListas(); } catch (Exception e) {}
                    construirNombresListas(); adapter.notifyDataSetChanged(); actualizarHeaderLista();
                }
            }
        }).setNegativeButton("Cancelar", null).show();
    }
    private void borrarLista(final String n) {
        new AlertDialog.Builder(this).setMessage("¿Borrar la lista \"" + n + "\"?").setPositiveButton("Borrar", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { listas.remove(n); guardarListas(); construirNombresListas(); adapter.notifyDataSetChanged(); actualizarHeaderLista(); }
        }).setNegativeButton("Cancelar", null).show();
    }

    private void pintarPlay(boolean playing) {
        btnPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (playing) tomarWake(); else liberarWake();
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
        public int getCount() { return modo == 0 ? (tab == 0 ? carpetas.size() : nombresListas.size()) : cancionesCarpeta.size(); }
        public Object getItem(int i) { return null; }
        public long getItemId(int i) { return i; }
        public View getView(int i, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.row_song, parent, false);
            TextView t = (TextView) v.findViewById(R.id.rowTitle);
            TextView a = (TextView) v.findViewById(R.id.rowArtist);
            TextView d = (TextView) v.findViewById(R.id.rowDur);
            if (modo == 0 && tab == 0) {
                Carpeta c = carpetas.get(i);
                t.setText(c.name); a.setText(c.songs.size() + " canciones"); d.setText(""); t.setTextColor(0xFFF4F4F8);
            } else if (modo == 0 && tab == 1) {
                String n = nombresListas.get(i); int cnt = 0;
                try { cnt = listas.getJSONArray(n).length(); } catch (Exception e) {}
                t.setText((n.equals("Favoritas") ? "★ " : "") + n); a.setText(cnt + " canciones"); d.setText(""); t.setTextColor(0xFFF4F4F8);
            } else {
                Song s = cancionesCarpeta.get(i);
                t.setText(s.title); a.setText(s.artist != null && s.artist.length() > 0 ? s.artist : "");
                d.setText(s.dur > 0 ? fmt(s.dur) : "");
                String actualPath = (posEnOrden >= 0 && posEnOrden < order.size() && order.get(posEnOrden) < songs.size())
                    ? songs.get(order.get(posEnOrden)).path : null;
                t.setTextColor(actualPath != null && actualPath.equals(s.path) ? accent : 0xFFF4F4F8);
            }
            return v;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(actualizador);
        try { if (usbReceiver != null) unregisterReceiver(usbReceiver); } catch (Exception e) {}
        try { if (netReceiver != null) unregisterReceiver(netReceiver); } catch (Exception e) {}
        try { if (volObserver != null) getContentResolver().unregisterContentObserver(volObserver); } catch (Exception e) {}
        liberarWake();
        try { if (eq != null) eq.release(); } catch (Exception e) {}
        try { if (mp != null) mp.release(); } catch (Exception e) {}
        eq = null; mp = null;
    }
}
