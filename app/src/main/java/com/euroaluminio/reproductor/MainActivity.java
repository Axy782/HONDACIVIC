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
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
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

    private void escanearMusica() {
        songs.clear();
        try {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.IS_MUSIC
            };
            Cursor c = getContentResolver().query(uri, cols, null, null,
                MediaStore.Audio.Media.TITLE + " ASC");
            if (c != null) {
                int iId = c.getColumnIndex(MediaStore.Audio.Media._ID);
                int iTit = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int iArt = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int iAlb = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int iDat = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int iMus = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC);
                while (c.moveToNext()) {
                    if (iMus >= 0 && c.getInt(iMus) == 0) continue;
                    Song s = new Song();
                    s.id = c.getLong(iId);
                    s.title = c.getString(iTit);
                    s.artist = c.getString(iArt);
                    s.albumId = iAlb >= 0 ? c.getLong(iAlb) : 0;
                    s.path = c.getString(iDat);
                    s.dur = iDur >= 0 ? c.getInt(iDur) : 0;
                    if (s.title == null) s.title = "(sin nombre)";
                    if (s.artist == null || s.artist.equals("<unknown>")) s.artist = "Desconocido";
                    songs.add(s);
                }
                c.close();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error leyendo la musica", Toast.LENGTH_LONG).show();
        }
        construirOrden();
        adapter.notifyDataSetChanged();
        txtCount.setText(songs.isEmpty() ? "No se encontro musica en el equipo" : (songs.size() + " canciones"));
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
        txtArtist.setText(s.artist);
        cargarCaratula(s);
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

    private void cargarCaratula(Song s) {
        Bitmap bmp = null;
        try {
            Uri artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), s.albumId);
            bmp = BitmapFactory.decodeStream(getContentResolver().openInputStream(artUri));
        } catch (Exception e) { bmp = null; }
        if (bmp != null) imgArt.setImageBitmap(bmp);
        else imgArt.setImageDrawable(new ColorDrawable(0xFF1C1C26));
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
            d.setText(fmt(s.dur));
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
