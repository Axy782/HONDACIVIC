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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

public class MainActivity extends Activity {

    static class Song {
        long id, albumId;
        String title, artist, path;
        int dur;
    }

    private final ArrayList<Song> songs = new ArrayList<Song>();
    private final ArrayList<Integer> order = new ArrayList<Integer>();
    private int posEnOrden = -1;

    private MediaPlayer mp;
    private Equalizer eq;
    private AudioManager am;

    private boolean shuffle = false;
    private int repeat = 0;
    private boolean prepared = false;

    private ImageView imgArt;
    private TextView txtTitle, txtArtist, txtCur, txtDur, txtCount;
    private SeekBar seek, vol;
    private ImageButton btnPlay, btnPrev, btnNext;
    private Button btnShuffle, btnRepeat, btnEq;
    private ListView list;
    private SongAdapter adapter;

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

        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", 0);
        eqEnabled = prefs.getBoolean("eqOn", true);
        pintarShuffle(); pintarRepeat();

        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int position, long idd) {
                reproducirCancion(position);
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
        vol.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
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
                        songs.clear();
                        songs.addAll(found);
                        construirOrden();
                        adapter.notifyDataSetChanged();
                        txtCount.setText(songs.isEmpty()
                            ? "No se encontró música (revisa el USB)"
                            : (songs.size() + " canciones"));
                    }
                });
            }
        }).start();
    }

    // Carpetas donde buscar: memoria interna, tarjetas y USB del carro
    private ArrayList<File> raices() {
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
            byte[] pic = r.getEmbeddedPicture();
            try { r.release(); } catch (Exception e) {}
            if (t != null && t.trim().length() > 0) { s.title = t; txtTitle.setText(t); }
            if (a != null && a.trim().length() > 0) { s.artist = a; txtArtist.setText(a); }
            else txtArtist.setText("Desconocido");
            if (pic != null) bmp = BitmapFactory.decodeByteArray(pic, 0, pic.length);
        } catch (Exception e) {}
        if (bmp != null) imgArt.setImageBitmap(bmp);
        else imgArt.setImageDrawable(new ColorDrawable(0xFF262633));
    }

    private void construirOrden() {
        order.clear();
        for (int i = 0; i < songs.size(); i++) order.add(i);
        if (shuffle) Collections.shuffle(order);
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
                    m.start();
                    pintarPlay(true);
                    handler.post(actualizador);
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

    private void pintarPlay(boolean playing) {
        btnPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }
    private void pintarShuffle() {
        btnShuffle.setTextColor(shuffle ? 0xFFFFB020 : 0xFF8B8B9A);
    }
    private void pintarRepeat() {
        btnRepeat.setText(repeat == 0 ? "Repetir" : repeat == 1 ? "Repetir: Todo" : "Repetir: Una");
        btnRepeat.setTextColor(repeat == 0 ? 0xFF8B8B9A : 0xFFFFB020);
    }

    private String fmt(int ms) {
        int s = ms / 1000; int m = s / 60; int x = s % 60;
        return m + ":" + (x < 10 ? "0" : "") + x;
    }

    private class SongAdapter extends BaseAdapter {
        public int getCount() { return songs.size(); }
        public Object getItem(int i) { return songs.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.row_song, parent, false);
            Song s = songs.get(i);
            TextView t = (TextView) v.findViewById(R.id.rowTitle);
            TextView a = (TextView) v.findViewById(R.id.rowArtist);
            TextView d = (TextView) v.findViewById(R.id.rowDur);
            t.setText(s.title);
            a.setText(s.artist);
            d.setText(s.dur>0?fmt(s.dur):"");
            boolean isCur = posEnOrden >= 0 && posEnOrden < order.size() && order.get(posEnOrden) == i;
            t.setTextColor(isCur ? 0xFFFFB020 : 0xFFF4F4F8);
            return v;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(actualizador);
        try { if (eq != null) eq.release(); } catch (Exception e) {}
        try { if (mp != null) mp.release(); } catch (Exception e) {}
        eq = null; mp = null;
    }
}
