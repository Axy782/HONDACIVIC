package com.euroaluminio.reproductor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Recibe las teclas de medios del volante (siguiente/anterior/play) y se las pasa a la app. */
public class MediaButtonReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
        KeyEvent ke = (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ke == null || ke.getAction() != KeyEvent.ACTION_DOWN || ke.getRepeatCount() > 0) return;   // solo el primer toque
        final MainActivity a = MainActivity.activo;
        if (a == null) return;
        final int code = ke.getKeyCode();
        final long down = ke.getDownTime();
        a.runOnUiThread(new Runnable() { public void run() { a.manejarTeclaMedia(code, down, "sistema"); } });
    }
}
