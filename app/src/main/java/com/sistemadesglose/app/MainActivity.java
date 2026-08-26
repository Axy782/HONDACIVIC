package com.sistemadesglose.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.ContactsContract;
import android.database.Cursor;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public static final String FILE_NAME = "sistema.html";
    private static final int PREFERRED_PORT = 47821;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PICK_UPDATE = 1002;
    private static final int REQ_ALL_FILES = 1003;

    private WebView web;
    private LocalServer server;
    private int port = PREFERRED_PORT;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;

    // ---------------------------------------------------------------- ciclo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences("jfv", MODE_PRIVATE);

        try {
            asegurarSistema();
        } catch (Exception e) {
            toast("Error copiando el sistema: " + e.getMessage());
        }

        try {
            server = new LocalServer(getFilesDir());
            port = server.start(PREFERRED_PORT);
        } catch (Exception e) {
            toast("No se pudo iniciar el servidor local: " + e.getMessage());
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f172a"));

        web = new WebView(this);
        configurarWebView(web);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // v053: boton flotante ⚙ eliminado completamente. Menu por Volumen Abajo.
        setContentView(root);

        cargarSistema();
        manejarIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        manejarIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Busqueda automatica de actualizaciones (solo si tiene permiso de archivos)
        if (tienePermisoArchivos()) buscarActualizacionAuto(false);
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    // v089.10: Callback cuando el usuario responde al diálogo de permisos.
    // Notifica al JavaScript el resultado para que continúe el flujo de importación.
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4711) {
            // 4711 = código que usamos en pedirPermisoContactos()
            final boolean concedido = (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            runOnUiThread(() -> {
                if (web != null) {
                    web.evaluateJavascript(
                        "if(typeof window.__onPermisoContactos==='function'){window.__onPermisoContactos(" 
                        + concedido + ");}",
                        null);
                }
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // v048: mantener presionado VOLUMEN ABAJO abre el menu de actualizacion
        // (reemplaza al boton flotante ⚙ que se quito)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                _volDownTime = System.currentTimeMillis();
            } else if (event.getRepeatCount() > 0 && !_menuAbriendo) {
                if (System.currentTimeMillis() - _volDownTime > 1200) {
                    _menuAbriendo = true;
                    mostrarMenu();
                    return true;
                }
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            // v103: preguntar al JS del sistema si puede navegar atrás por su cuenta.
            // window.__navBack() cierra modales, hace pop del stack de vistas, etc.
            // Retorna true si manejó el back, false si no hay a dónde ir (menú principal).
            web.evaluateJavascript(
                "(function(){try{return (typeof window.__navBack === 'function') ? !!window.__navBack() : false;}catch(e){return false;}})();",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String result) {
                        boolean manejado = "true".equals(result);
                        if (!manejado) {
                            // No hay a dónde ir atrás en el sistema → dejar comportamiento nativo:
                            // si el WebView tiene historial de URLs úsalo, si no cerrar la app.
                            if (web.canGoBack()) { web.goBack(); }
                            else { finish(); }
                        }
                    }
                }
            );
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private long _volDownTime = 0;
    private boolean _menuAbriendo = false;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            _menuAbriendo = false;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------- webview

    private void configurarWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // v089.11: BLOQUEAR completamente el zoom con pellizco en el WebView.
        // Antes estaba en true, lo que permitia agrandar/achicar con los dedos.
        // Ahora el sistema mantiene siempre el mismo tamaño (mejor UX y previene
        // que el usuario deje la UI zooneada por accidente).
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);

        w.addJavascriptInterface(new Puente(), "AndroidApp");
        
        // v089.11: Refuerzo extra — bloquear gestos multitouch a nivel WebView
        // (por si algún ROM/versión de WebView ignora setSupportZoom(false))
        w.setOnTouchListener((view, event) -> {
            if (event.getPointerCount() > 1) {
                // Ignorar gestos de 2+ dedos (pinch-zoom)
                return true;
            }
            return false;
        });

        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(JS_PUENTE, null);
            }
            // v057: interceptar URLs externas (whatsapp://, tel:, mailto:, market://, etc)
            // El WebView no sabe abrir esquemas propios de otras apps; hay que lanzarlos como Intent.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return manejarUrlExterna(request.getUrl().toString());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return manejarUrlExterna(url);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(Intent.createChooser(i, "Seleccionar archivo"), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                abrirVentanaHija(resultMsg);
                return true;
            }
        });

        w.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url != null && url.startsWith("data:")) {
                try {
                    int coma = url.indexOf(',');
                    String b64 = url.substring(coma + 1);
                    guardarEnDescargas(Base64.decode(b64, Base64.DEFAULT), "descarga_" + System.currentTimeMillis());
                } catch (Exception e) { toast("No se pudo descargar"); }
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) { toast("No se pudo abrir la descarga"); }
            }
        });
    }

    private void cargarSistema() {
        web.loadUrl("http://127.0.0.1:" + port + "/" + FILE_NAME + "?t=" + System.currentTimeMillis());
    }

    // v057: manejar URLs con esquemas externos (whatsapp://, tel:, mailto:, etc)
    // Retorna true si el URL fue manejado (interceptado) y no debe cargarse en el WebView.
    private boolean manejarUrlExterna(String url) {
        if (url == null) return false;
        // Dejar que el WebView cargue URLs locales normales (http/https)
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // Excepcion: wa.me redirige a whatsapp:// → mejor abrirlo como Intent directo
            if (url.contains("wa.me/") || url.contains("api.whatsapp.com/")) {
                return abrirComoIntent(url);
            }
            return false; // seguir cargando normalmente en el WebView
        }
        // Cualquier otro esquema (whatsapp://, tel:, mailto:, market://, geo:, sms:...)
        // se lanza como Intent externo para que Android lo dirija a la app correcta.
        return abrirComoIntent(url);
    }

    private boolean abrirComoIntent(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            toast("No hay app para abrir: " + url);
            return true; // aunque falle, evitar que el WebView lo intente
        }
    }

    /** Ventana hija (window.open) con barra de IMPRIMIR / CERRAR. */
    // v466: referencia a la ventana hija de documentos activa, para poder
    // reajustar su zoom cuando el teléfono gira (onConfigurationChanged).
    private WebView _hijaActiva = null;
    private Dialog _dialogHijaActivo = null;

    private void abrirVentanaHija(android.os.Message resultMsg) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setBackgroundColor(Color.WHITE);

        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setBackgroundColor(Color.parseColor("#1e293b"));
        barra.setPadding(dp(8), dp(8), dp(8), dp(8));

        final WebView hija = new WebView(this);
        configurarWebView(hija);
        // v107k: en la vista previa de documentos, HABILITAR zoom con pellizco.
        // El WebView principal tiene zoom bloqueado (setSupportZoom(false)) para la UI del sistema,
        // pero cuando se abre una ventana hija con un documento imprimible/PDF-like, el usuario
        // debe poder acercar/alejar con los dedos como en un lector de PDF.
        WebSettings sh = hija.getSettings();
        sh.setSupportZoom(true);
        sh.setBuiltInZoomControls(true);
        sh.setDisplayZoomControls(false); // sin botones +/- en pantalla, solo pinch
        // Quitar el bloqueador multitouch heredado de configurarWebView (setOnTouchListener con 2+ dedos)
        hija.setOnTouchListener(null);

        // ═══════════════════════════════════════════════════════════════════
        // v470: ZOOM AL GIRAR — usando el MISMO zoom que controlan los dedos.
        // ───────────────────────────────────────────────────────────────────
        // Por qué los intentos anteriores fallaron: el WebView mantiene su
        // PROPIO nivel de zoom (el del pinch) y ese manda sobre el CSS zoom y
        // sobre el meta viewport. Por eso nada de eso hacía efecto.
        // La solución: zoomBy(), que cambia ESE zoom (el de los dedos) por
        // software, con el factor exacto para que el desglose quepa completo.
        hija.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private int ultimoAncho = -1;
            private float escalaOriginal = -1f;   // zoom "normal" con el que abrió
            @Override
            public void onLayoutChange(View v, int l, int t2, int r, int b,
                                       int ol, int ot, int or_, int ob) {
                final int ancho = r - l;
                final int alto  = b - t2;
                if(ancho <= 0) return;
                if(Math.abs(ancho - ultimoAncho) < 20) return;  // no cambió de verdad
                final boolean primeraVez = (ultimoAncho == -1);
                ultimoAncho = ancho;
                if(primeraVez){
                    // Al abrir: guardar el zoom "normal" del documento para poder
                    // volver EXACTAMENTE a él cuando el teléfono regrese a vertical.
                    hija.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                float s = hija.getScale();
                                if(s > 0) escalaOriginal = s;
                            } catch(Exception e){}
                        }
                    }, 500);
                    return;                          // al abrir no tocar el zoom
                }
                final boolean horizontal = ancho > alto;
                // esperar a que el giro termine de acomodar el layout
                hija.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            // medir el ancho del contenido en píxeles CSS
                            hija.evaluateJavascript(
                                "(function(){try{" +
                                "var b=document.body, mx=0;" +
                                "if(b){mx=Math.max(b.scrollWidth||0,b.offsetWidth||0);" +
                                "var t=b.getElementsByTagName('*');" +
                                "for(var i=0;i<t.length;i++){var w=t[i].scrollWidth||0,o=t[i].offsetWidth||0,m=w>o?w:o;if(m>mx)mx=m;}}" +
                                "return mx;}catch(e){return 0;}})();",
                                new android.webkit.ValueCallback<String>() {
                                    @Override public void onReceiveValue(String value) {
                                        try {
                                            double contenidoCSS = 0;
                                            if(value != null){
                                                String s = value.replace("\"","").trim();
                                                if(!s.equals("null") && s.length() > 0)
                                                    contenidoCSS = Double.parseDouble(s);
                                            }
                                            float escalaActual = hija.getScale();   // px device por px CSS
                                            if(escalaActual <= 0) escalaActual = 1f;
                                            float escalaDeseada;
                                            if(horizontal && contenidoCSS > 0){
                                                // que el contenido completo quepa a lo ancho
                                                escalaDeseada = (float)(ancho / contenidoCSS);
                                                if(escalaDeseada < 0.1f) escalaDeseada = 0.1f;
                                                if(escalaDeseada > 1.0f) escalaDeseada = 1.0f;
                                            } else {
                                                // VERTICAL: volver al zoom con el que
                                                // abrió el documento (no asumir 1.0,
                                                // que varía según la pantalla).
                                                escalaDeseada = (escalaOriginal > 0)
                                                              ? escalaOriginal : 1.0f;
                                            }
                                            float factor = escalaDeseada / escalaActual;
                                            if(factor < 0.01f) factor = 0.01f;
                                            if(factor > 100f)  factor = 100f;
                                            // zoomBy cambia el zoom REAL del WebView (el de los dedos)
                                            hija.zoomBy(factor);
                                            // Tras cambiar el zoom, llevar la vista al INICIO del
                                            // documento (arriba-izquierda): tras el giro suele
                                            // quedar desplazada hacia abajo.
                                            final boolean irArriba = !horizontal;
                                            hija.postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    try { if(irArriba){ hija.scrollTo(0, 0); } } catch(Exception e){}
                                                }
                                            }, 120);
                                            // Refuerzo: volver a corregir si quedó
                                            // desviado (la escala tarda en asentarse).
                                            final float objetivo = escalaDeseada;
                                            final boolean irArriba2 = !horizontal;
                                            hija.postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    try {
                                                        float ahora = hija.getScale();
                                                        if(ahora <= 0) return;
                                                        float dif = objetivo / ahora;
                                                        // solo si está desviado más de un 3%
                                                        if(dif < 0.97f || dif > 1.03f){
                                                            if(dif < 0.01f) dif = 0.01f;
                                                            if(dif > 100f)  dif = 100f;
                                                            hija.zoomBy(dif);
                                                        }
                                                        if(irArriba2){ hija.scrollTo(0, 0); }
                                                    } catch(Exception e){}
                                                }
                                            }, 350);
                                        } catch(Exception e){}
                                    }
                                }
                            );
                        } catch(Exception e){}
                    }
                }, 450);
            }
        });

        // v464: botón IMPRIMIR quitado — el celular no imprime, solo confunde.
        // (se conservan GUARDAR PDF, GUARDAR IMG y CERRAR)

        Button bPdf = new Button(this);
        bPdf.setText("GUARDAR PDF");
        bPdf.setOnClickListener(v -> guardarComoPDF(hija));

        Button bImg = new Button(this);
        bImg.setText("GUARDAR IMG");
        bImg.setOnClickListener(v -> guardarComoImagen(hija));

        Button bClose = new Button(this);
        bClose.setText("CERRAR");
        bClose.setOnClickListener(v -> d.dismiss());

        // v466: al cerrar la ventana, olvidar la referencia
        d.setOnDismissListener(dlg -> {
            if(_hijaActiva == hija) _hijaActiva = null;
            if(_dialogHijaActivo == d) _dialogHijaActivo = null;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        barra.addView(bPdf, lp);
        barra.addView(bImg, lp);
        barra.addView(bClose, lp);

        cont.addView(barra, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cont.addView(hija, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        d.setContentView(cont);
        d.show();
        // v466: recordar esta ventana para reajustar el zoom al girar
        _hijaActiva = hija;
        _dialogHijaActivo = d;

        android.webkit.WebView.WebViewTransport t = (android.webkit.WebView.WebViewTransport) resultMsg.obj;
        t.setWebView(hija);
        resultMsg.sendToTarget();
    }

    private void imprimir(WebView target, String nombre) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String job = "Sistema JFV - " + nombre;
            pm.print(job, target.createPrintDocumentAdapter(job),
                    new PrintAttributes.Builder().build());
        } catch (Exception e) {
            toast("No se pudo imprimir: " + e.getMessage());
        }
    }

    // v047: guarda el contenido del WebView como IMAGEN JPG en Descargas (con aviso)
    private void guardarComoImagen(WebView target) {
        try {
            toast("Generando imagen...");
            // Pedir al JS que genere la imagen del documento con html2canvas
            String js =
                "(function(){try{" +
                "var el=document.querySelector('.hoja-doc')||document.querySelector('.__hoja')||document.body;" +
                "var tb=document.querySelector('.__doctb'); if(tb) tb.style.display='none';" +
                "if(typeof html2canvas==='undefined'){return 'NOLIB';}" +
                "return 'OK';}catch(e){return 'ERR:'+e.message;}})();";
            target.evaluateJavascript(js, valor -> {
                String v = valor != null ? valor.replace("\"", "") : "";
                if (v.startsWith("OK")) {
                    // La libreria esta lista → generar y capturar
                    capturarImagenWebView(target);
                } else if (v.startsWith("NOLIB")) {
                    // Cargar html2canvas desde CDN y reintentar
                    cargarLibYCapturar(target);
                } else {
                    toast("No se pudo preparar la imagen");
                }
            });
        } catch (Exception e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void cargarLibYCapturar(WebView target) {
        String js =
            "(function(){var s=document.createElement('script');" +
            "s.src='https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js';" +
            "s.onload=function(){window.__libLista=true;};" +
            "document.head.appendChild(s);})();";
        target.evaluateJavascript(js, null);
        // Esperar 2 segundos a que cargue y luego capturar
        target.postDelayed(() -> capturarImagenWebView(target), 2500);
    }

    private void capturarImagenWebView(WebView target) {
        String js =
            "(function(){try{" +
            "document.body.classList.add('__cap');" +
            "var el=document.querySelector('.hoja-doc')||document.querySelector('.__hoja')||document.body;" +
            "return new Promise(function(resolve){" +
            "html2canvas(el,{scale:2,useCORS:true,backgroundColor:'#ffffff',logging:false}).then(function(canvas){" +
            "resolve(canvas.toDataURL('image/jpeg',0.92));" +
            "}).catch(function(e){resolve('ERR:'+e.message);});" +
            "});}catch(e){return 'ERR:'+e.message;}})();";
        // evaluateJavascript no espera Promesas, asi que usamos otro enfoque:
        // pedirle al JS que guarde el resultado en una variable global y lo leemos
        //
        // v074: FIX definitivo del espacio blanco. El problema: el desglose tiene una
        // tabla ancha adentro, pero el contenedor .hoja-doc puede ser más ancho que la
        // tabla (por padding/centrado) o más angosto. La solución: medir el ANCHO REAL
        // que ocupa el contenido visible y capturar EXACTAMENTE ese ancho, sin forzar
        // estilos que dejen espacio en blanco.
        String jsAsync =
            "(function(){try{" +
            "document.body.classList.add('__cap');" +
            "var el=document.querySelector('.hoja-doc')||document.querySelector('.__hoja')||document.body;" +
            "if(!el) return 'ERR:no encontro elemento';" +
            // Quitar temporalmente el max-width de descendientes (cotizaciones con 210mm)
            "window.__hijosModImg = [];" +
            "var hijosImg = el.querySelectorAll('*');" +
            "for(var h=0; h<hijosImg.length; h++){" +
            "  var hijo = hijosImg[h];" +
            "  var mw = window.getComputedStyle(hijo).maxWidth;" +
            "  if(mw && mw !== 'none' && mw !== '' && !mw.includes('%')){" +
            "    window.__hijosModImg.push({el: hijo, mw: hijo.style.maxWidth});" +
            "    hijo.style.maxWidth = 'none';" +
            "  }" +
            "}" +
            "el.offsetHeight;" +  // reflow tras quitar max-width
            // Medir el ancho REAL que ocupa el contenido (el scrollWidth del elemento
            // incluye todo lo que se desborda, que es justo lo que queremos capturar)
            "var anchoReal = el.scrollWidth;" +
            "var altoReal  = el.scrollHeight;" +
            // Revisar si algún hijo se desborda aún más (por si acaso)
            "var todos = el.querySelectorAll('*');" +
            "for(var i=0;i<todos.length;i++){" +
            "  var r = todos[i].getBoundingClientRect();" +
            "  var derecha = todos[i].offsetLeft + todos[i].offsetWidth;" +
            "  if(derecha > anchoReal) anchoReal = derecha;" +
            "}" +
            "window.__imgLista=null;" +
            "function _restaurarImg(){" +
            "  try {" +
            "    document.body.classList.remove('__cap');" +
            "    var hm = window.__hijosModImg || [];" +
            "    for(var k=0; k<hm.length; k++){" +
            "      try { hm[k].el.style.maxWidth = hm[k].mw || ''; } catch(er){}" +
            "    }" +
            "  } catch(er){}" +
            "}" +
            "html2canvas(el,{" +
            "  scale: 2," +
            "  useCORS: true," +
            "  backgroundColor: '#ffffff'," +
            "  logging: false," +
            "  width: anchoReal," +
            "  height: altoReal," +
            "  windowWidth: anchoReal," +
            "  scrollX: 0, scrollY: 0" +
            "}).then(function(canvas){" +
            "  window.__imgLista=canvas.toDataURL('image/jpeg',0.92);" +
            "  _restaurarImg();" +
            "}).catch(function(e){" +
            "  window.__imgLista='ERR:'+e.message;" +
            "  _restaurarImg();" +
            "});" +
            "return 'INICIADO';}catch(e){return 'ERR:'+e.message;}})();";
        target.evaluateJavascript(jsAsync, null);
        // Revisar cada 500ms si ya termino (max 15 seg)
        esperarImagen(target, 0);
    }

    private void esperarImagen(WebView target, int intentos) {
        if (intentos > 30) { toast("Tardó demasiado. Intenta de nuevo."); restaurarToolbar(target); return; }
        target.evaluateJavascript("window.__imgLista", valor -> {
            if (valor == null || valor.equals("null")) {
                target.postDelayed(() -> esperarImagen(target, intentos + 1), 500);
            } else {
                String dataUrl = valor;
                if (dataUrl.startsWith("\"")) dataUrl = dataUrl.substring(1);
                if (dataUrl.endsWith("\"")) dataUrl = dataUrl.substring(0, dataUrl.length() - 1);
                dataUrl = dataUrl.replace("\\/", "/");
                if (dataUrl.startsWith("ERR:")) {
                    toast("No se pudo generar la imagen");
                    restaurarToolbar(target);
                    return;
                }
                try {
                    int coma = dataUrl.indexOf(',');
                    String b64 = dataUrl.substring(coma + 1);
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    String nombre = "Desglose_" + System.currentTimeMillis() + ".jpg";
                    guardarEnDescargas(data, nombre);
                    // guardarEnDescargas ya muestra el toast "Guardado en Descargas: ..."
                } catch (Exception e) {
                    toast("Error al guardar: " + e.getMessage());
                }
                restaurarToolbar(target);
            }
        });
    }

    // v047: guarda el WebView como PDF en Descargas (con aviso)
    private void guardarComoPDF(WebView target) {
        try {
            toast("Generando PDF...");
            String js =
                "(function(){try{" +
                "if(typeof html2pdf==='undefined'){" +
                "var s=document.createElement('script');" +
                "s.src='https://cdnjs.cloudflare.com/ajax/libs/html2pdf.js/0.10.1/html2pdf.bundle.min.js';" +
                "s.onload=function(){window.__pdfLibLista=true;};document.head.appendChild(s);" +
                "return 'CARGANDO';}return 'LISTA';}catch(e){return 'ERR:'+e.message;}})();";
            target.evaluateJavascript(js, valor -> {
                String v = valor != null ? valor.replace("\"", "") : "";
                if (v.startsWith("LISTA")) {
                    generarPDF(target);
                } else {
                    // Esperar que cargue la lib
                    target.postDelayed(() -> generarPDF(target), 2500);
                }
            });
        } catch (Exception e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void generarPDF(WebView target) {
        // v073: PDF más robusto. Carga las librerías si no están disponibles,
        // usa min-width en lugar de forzar width (para evitar espacio en blanco),
        // tiene fallback a html2pdf si jsPDF no está.
        String js =
            "(function(){try{" +
            "document.body.classList.add('__cap');" +
            "var el=document.querySelector('.hoja-doc')||document.querySelector('.__hoja')||document.body;" +
            "if(!el) return 'ERR:no encontro elemento';" +
            "var tb=document.querySelector('.__doctb'); if(tb) tb.style.display='none';" +
            // Quitar el max-width de descendientes
            "window.__hijosMod = [];" +
            "var hijos = el.querySelectorAll('*');" +
            "for(var h=0; h<hijos.length; h++){" +
            "  var hijo = hijos[h];" +
            "  var mw = window.getComputedStyle(hijo).maxWidth;" +
            "  if(mw && mw !== 'none' && mw !== '' && !mw.includes('%')){" +
            "    window.__hijosMod.push({el: hijo, mw: hijo.style.maxWidth});" +
            "    hijo.style.maxWidth = 'none';" +
            "  }" +
            "}" +
            "el.offsetHeight;" +  // reflow
            // v074: Medir ancho REAL del contenido con scrollWidth (sin forzar body)
            "var anchoReal = el.scrollWidth;" +
            "var altoReal  = el.scrollHeight;" +
            "var todos = el.querySelectorAll('*');" +
            "for(var i=0;i<todos.length;i++){" +
            "  var derecha = todos[i].offsetLeft + todos[i].offsetWidth;" +
            "  if(derecha > anchoReal) anchoReal = derecha;" +
            "}" +
            "window.__pdfLista=null;" +
            "function _restaurar(){" +
            "  try {" +
            "    document.body.classList.remove('__cap');" +
            "    var hm = window.__hijosMod || [];" +
            "    for(var k=0; k<hm.length; k++){" +
            "      try { hm[k].el.style.maxWidth = hm[k].mw || ''; } catch(er){}" +
            "    }" +
            "  } catch(er){}" +
            "}" +
            // v075: usar html2pdf() directo (metodo confiable que SI genera el PDF).
            // Convertimos px a mm para el tamaño de pagina exacto (sin espacio blanco).
            "function _hacerPDF(){" +
            "  try {" +
            "    var pxAmm = 25.4 / 96;" +   // 96 dpi
            "    var anchoMM = anchoReal * pxAmm;" +
            "    var altoMM  = altoReal  * pxAmm;" +
            "    var margen = 3;" +
            "    var pageW = anchoMM + margen * 2;" +
            "    var pageH = altoMM  + margen * 2;" +
            "    var opt = {" +
            "      margin: margen," +
            "      image: { type:'jpeg', quality:0.92 }," +
            "      html2canvas: {" +
            "        scale: 2," +
            "        useCORS: true," +
            "        backgroundColor: '#ffffff'," +
            "        logging: false," +
            "        width: anchoReal," +
            "        height: altoReal," +
            "        windowWidth: anchoReal," +
            "        scrollX: 0, scrollY: 0" +
            "      }," +
            "      jsPDF: {" +
            "        unit: 'mm'," +
            "        format: [pageW, pageH]," +
            "        orientation: pageW > pageH ? 'landscape' : 'portrait'," +
            "        compress: true" +
            "      }" +
            "    };" +
            "    window.html2pdf().set(opt).from(el).outputPdf('datauristring').then(function(dataUrl){" +
            "      window.__pdfLista = dataUrl;" +
            "      _restaurar();" +
            "    }).catch(function(err){" +
            "      window.__pdfLista = 'ERR:' + (err.message||err);" +
            "      _restaurar();" +
            "    });" +
            "  } catch(e){" +
            "    window.__pdfLista = 'ERR:' + e.message;" +
            "    _restaurar();" +
            "  }" +
            "}" +
            // Cargar html2pdf.bundle si no está (trae html2canvas + jsPDF adentro)
            "if(typeof html2pdf === 'undefined'){" +
            "  var s = document.createElement('script');" +
            "  s.src = 'https://cdnjs.cloudflare.com/ajax/libs/html2pdf.js/0.10.1/html2pdf.bundle.min.js';" +
            "  s.onload = function(){ setTimeout(_hacerPDF, 150); };" +
            "  s.onerror = function(){" +
            "    window.__pdfLista = 'ERR:no se pudo cargar html2pdf desde CDN';" +
            "    _restaurar();" +
            "  };" +
            "  document.head.appendChild(s);" +
            "} else {" +
            "  _hacerPDF();" +
            "}" +
            "return 'INICIADO';}catch(e){return 'ERR:'+e.message;}})();";
        target.evaluateJavascript(js, null);
        esperarPDF(target, 0);
    }

    private void esperarPDF(WebView target, int intentos) {
        if (intentos > 40) { toast("Tardó demasiado. Intenta de nuevo."); restaurarToolbar(target); return; }
        target.evaluateJavascript("window.__pdfLista", valor -> {
            if (valor == null || valor.equals("null")) {
                target.postDelayed(() -> esperarPDF(target, intentos + 1), 500);
            } else {
                String dataUrl = valor;
                if (dataUrl.startsWith("\"")) dataUrl = dataUrl.substring(1);
                if (dataUrl.endsWith("\"")) dataUrl = dataUrl.substring(0, dataUrl.length() - 1);
                dataUrl = dataUrl.replace("\\/", "/");
                if (dataUrl.startsWith("ERR:")) {
                    toast("No se pudo generar el PDF");
                    restaurarToolbar(target);
                    return;
                }
                try {
                    int coma = dataUrl.indexOf(',');
                    String b64 = dataUrl.substring(coma + 1);
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    String nombre = "Desglose_" + System.currentTimeMillis() + ".pdf";
                    guardarEnDescargas(data, nombre);
                } catch (Exception e) {
                    toast("Error al guardar: " + e.getMessage());
                }
                restaurarToolbar(target);
            }
        });
    }

    private void restaurarToolbar(WebView target) {
        target.evaluateJavascript(
            "(function(){var tb=document.querySelector('.__doctb'); if(tb) tb.style.display='';})();", null);
    }

    // ------------------------------------------------------- puente con JS

    /** Se inyecta en cada pagina cargada. */
    private static final String JS_PUENTE =
        "(function(){if(window.__jfv)return;window.__jfv=1;" +
        "window.print=function(){try{AndroidApp.imprimir()}catch(e){}};" +
        "function env(b,n){var r=new FileReader();r.onloadend=function(){" +
        "try{AndroidApp.guardarBase64(String(r.result).split(',')[1],n)}catch(e){}};r.readAsDataURL(b);}" +
        "var oc=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){var h=this.getAttribute('href')||'';" +
        "if(this.hasAttribute('download')&&(h.indexOf('blob:')===0||h.indexOf('data:')===0)){" +
        "var n=this.getAttribute('download')||('archivo_'+Date.now());" +
        "fetch(h).then(function(r){return r.blob()}).then(function(b){env(b,n)});return;}" +
        "return oc.apply(this,arguments);};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;" +
        "if(!a)return;var h=a.getAttribute('href')||'';" +
        "if(h.indexOf('blob:')===0||h.indexOf('data:')===0){e.preventDefault();" +
        "var n=a.getAttribute('download')||('archivo_'+Date.now());" +
        "fetch(h).then(function(r){return r.blob()}).then(function(b){env(b,n)});}},true);" +
        "})();";

    public class Puente {
        @JavascriptInterface public void imprimir() {
            runOnUiThread(() -> MainActivity.this.imprimir(web, "Sistema"));
        }
        @JavascriptInterface public void guardarBase64(String b64, String nombre) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                guardarEnDescargas(data, nombre);
            } catch (Exception e) {
                runOnUiThread(() -> toast("No se pudo guardar"));
            }
        }
        // v041: compartir un archivo (imagen/pdf) por el share nativo de Android
        // Abre el selector con WhatsApp, Gmail, Drive, etc. — con el archivo YA adjunto
        @JavascriptInterface public void compartirBase64(String b64, String nombre, String mensaje) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                compartirArchivo(data, nombre, mensaje, null);
            } catch (Exception e) {
                runOnUiThread(() -> toast("No se pudo compartir"));
            }
        }
        // v041: compartir directo a WhatsApp (si esta instalado) con el archivo adjunto
        @JavascriptInterface public void compartirWhatsApp(String b64, String nombre, String mensaje) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                compartirArchivo(data, nombre, mensaje, "com.whatsapp");
            } catch (Exception e) {
                runOnUiThread(() -> toast("No se pudo abrir WhatsApp"));
            }
        }
        @JavascriptInterface public void menu() { runOnUiThread(() -> mostrarMenu()); }
        @JavascriptInterface public String version() { return versionInstalada(); }
        // v044: ID unico y estable del celular (para amarrar la licencia a ESTE telefono)
        @JavascriptInterface public String deviceId() { return obtenerDeviceId(); }
        
        // v089.10: Verifica si tiene permiso para leer contactos.
        // Retorna "granted", "denied" o "needed" (aun no lo ha pedido)
        @JavascriptInterface public String permisoContactosEstado() {
            try {
                int status = checkSelfPermission(android.Manifest.permission.READ_CONTACTS);
                return (status == PackageManager.PERMISSION_GRANTED) ? "granted" : "needed";
            } catch (Exception e) {
                return "denied";
            }
        }
        
        // v089.10: Pide el permiso de contactos al usuario.
        // Cuando el usuario responde, se vuelve a evaluar via permisoContactosEstado().
        // El sistema HTML debe llamar a esto y luego volver a intentar obtenerContactos().
        @JavascriptInterface public void pedirPermisoContactos() {
            runOnUiThread(() -> {
                try {
                    requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 4711);
                } catch (Exception e) {
                    toast("No se pudo pedir permiso de contactos");
                }
            });
        }
        
        // v103: Instalar un nuevo HTML del sistema (recibido por WhatsApp/importado).
        // El HTML nuevo se guarda en el filesystem interno y la próxima recarga lo usa.
        // Retorna "OK:versión" si se instaló, o "ERROR:mensaje" si falló.
        @JavascriptInterface public String instalarSistemaHTML(String html) {
            try {
                if (html == null || html.length() < 1000) {
                    return "ERROR:HTML muy corto o vacío";
                }
                // Verificar que sea un HTML de este sistema (contiene SISTEMA_VERSION)
                if (!html.contains("SISTEMA_VERSION")) {
                    return "ERROR:El archivo no parece ser del Sistema Desglose (falta SISTEMA_VERSION)";
                }
                File f = archivoSistema();
                FileOutputStream out = new FileOutputStream(f);
                out.write(html.getBytes("UTF-8"));
                out.close();
                String version = leerVersion(f);
                prefs.edit().putString("version", version).apply();
                runOnUiThread(() -> toast("✓ Sistema actualizado a " + version + " · Reinicia la app"));
                return "OK:" + version;
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }
        
        // v103: Recargar la WebView para aplicar el HTML recién instalado.
        @JavascriptInterface public void recargarSistema() {
            runOnUiThread(() -> {
                try {
                    if (web != null) web.reload();
                } catch (Exception e) {
                    toast("Error al recargar: " + e.getMessage());
                }
            });
        }

        // ═══ v108n: PRUEBA GRATIS DE 10 MINUTOS ═══
        // El timestamp de inicio de prueba se guarda en un archivo oculto en
        // almacenamiento EXTERNO (carpeta Downloads con nombre discreto).
        // Esto hace que sobreviva la DESINSTALACIÓN de la app — si el usuario
        // reinstala, el archivo sigue ahí y el sistema sabe que ya usó su prueba.
        //
        // leerPruebaInicio() → retorna el timestamp (ms) guardado, o "" si no existe.
        // guardarPruebaInicio(ts) → guarda el timestamp la primera vez.
        @JavascriptInterface public String leerPruebaInicio() {
            try {
                // 1) Intentar leer del almacenamiento externo (sobrevive reinstalar)
                String valExterno = leerPruebaExterno();
                if (valExterno != null && !valExterno.isEmpty()) {
                    // Sincronizar también en prefs internas por si acaso
                    try { prefs.edit().putString("prueba_inicio", valExterno).apply(); } catch (Exception e) {}
                    return valExterno;
                }
                // 2) Fallback: prefs internas (se borran al desinstalar, pero por si el externo falla)
                String valInterno = prefs.getString("prueba_inicio", "");
                return valInterno == null ? "" : valInterno;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface public void guardarPruebaInicio(String ts) {
            try {
                if (ts == null || ts.isEmpty()) return;
                // Guardar en prefs internas
                try { prefs.edit().putString("prueba_inicio", ts).apply(); } catch (Exception e) {}
                // Guardar en almacenamiento externo (sobrevive reinstalar)
                guardarPruebaExterno(ts);
            } catch (Exception e) {}
        }
        
        // v91: Lee TODOS los contactos con teléfono desde la agenda del celular,
        // INCLUYENDO la foto del contacto si tiene una asignada.
        // Retorna JSON: [{"nombre":"...","telefono":"...","telefono2":"...","foto":"data:image/jpeg;base64,..."}]
        // Requiere permiso READ_CONTACTS previamente concedido.
        @JavascriptInterface public String obtenerContactos() {
            try {
                int status = checkSelfPermission(android.Manifest.permission.READ_CONTACTS);
                if (status != PackageManager.PERMISSION_GRANTED) {
                    return "ERROR:NO_PERMISO";
                }
                // Estructura: contactId -> {nombre, [telefonos...]}
                java.util.Map<String, String> nombres = new java.util.LinkedHashMap<>();
                java.util.Map<String, java.util.List<String>> tels = new java.util.LinkedHashMap<>();
                java.util.Map<String, Boolean> tieneFoto = new java.util.LinkedHashMap<>();
                
                android.net.Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
                String[] proyeccion = new String[] {
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_ID
                };
                String orden = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC";
                Cursor cursor = getContentResolver().query(uri, proyeccion, null, null, orden);
                if (cursor == null) return "[]";
                try {
                    int idxNombre = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY);
                    if (idxNombre < 0) idxNombre = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int idxTel = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    int idxId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                    int idxFotoId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_ID);
                    while (cursor.moveToNext()) {
                        String nombre = idxNombre >= 0 ? cursor.getString(idxNombre) : null;
                        String tel = idxTel >= 0 ? cursor.getString(idxTel) : null;
                        String id = idxId >= 0 ? cursor.getString(idxId) : "";
                        long fotoId = idxFotoId >= 0 ? cursor.getLong(idxFotoId) : 0;
                        if (nombre == null) nombre = "";
                        nombre = nombre.trim();
                        if (tel == null) tel = "";
                        String telLimpio = tel.replaceAll("[^0-9+]", "").replaceAll("(?<!^)\\+", "");
                        if (telLimpio.length() < 7) continue;
                        
                        String nombreDigitos = nombre.replaceAll("[^0-9+]", "").replaceAll("(?<!^)\\+", "");
                        if (!nombreDigitos.isEmpty() && nombreDigitos.equals(telLimpio)) {
                            nombre = "";
                        }
                        
                        String clave = id.isEmpty() ? ("SIN_ID_" + tel) : id;
                        if (!nombres.containsKey(clave)) {
                            nombres.put(clave, nombre);
                        } else if (nombres.get(clave).isEmpty() && !nombre.isEmpty()) {
                            nombres.put(clave, nombre);
                        }
                        if (!tels.containsKey(clave)) tels.put(clave, new java.util.ArrayList<String>());
                        java.util.List<String> t = tels.get(clave);
                        if (!t.contains(telLimpio) && t.size() < 2) t.add(telLimpio);
                        // Marcar que este contacto tiene foto (fotoId > 0 significa que tiene)
                        if (fotoId > 0) tieneFoto.put(clave, true);
                    }
                } finally {
                    cursor.close();
                }
                
                // Construir JSON
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                boolean primero = true;
                for (String clave : nombres.keySet()) {
                    java.util.List<String> t = tels.get(clave);
                    if (t == null || t.isEmpty()) continue;
                    String nombre = nombres.get(clave);
                    if (nombre == null) nombre = "";
                    if (!primero) sb.append(",");
                    primero = false;
                    sb.append("{\"nombre\":\"").append(escapeJson(nombre)).append("\"");
                    sb.append(",\"telefono\":\"").append(escapeJson(t.get(0))).append("\"");
                    sb.append(",\"telefono2\":\"").append(t.size() > 1 ? escapeJson(t.get(1)) : "").append("\"");
                    // v91: leer foto del contacto si tiene una asignada
                    String fotoDataUrl = "";
                    if (Boolean.TRUE.equals(tieneFoto.get(clave)) && !clave.startsWith("SIN_ID_")) {
                        fotoDataUrl = leerFotoContacto(clave);
                    }
                    sb.append(",\"foto\":\"").append(escapeJson(fotoDataUrl)).append("\"");
                    sb.append("}");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }
        
        // v91: Lee la foto del contacto (thumbnail) y la retorna como data URL base64.
        // Se usa el thumbnail (más pequeño) en vez de la foto full para no cargar mucho.
        private String leerFotoContacto(String contactId) {
            java.io.InputStream in = null;
            try {
                android.net.Uri contactUri = android.net.Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI, contactId);
                // openContactPhotoInputStream: false = thumbnail (chico), true = foto full
                in = ContactsContract.Contacts.openContactPhotoInputStream(
                    getContentResolver(), contactUri, false);
                if (in == null) return "";
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int totalBytes = 0;
                final int MAX_BYTES = 200_000;  // límite de 200 KB por foto
                while ((n = in.read(buf)) > 0) {
                    totalBytes += n;
                    if (totalBytes > MAX_BYTES) return "";  // foto muy grande, saltarla
                    bos.write(buf, 0, n);
                }
                byte[] data = bos.toByteArray();
                if (data.length == 0) return "";
                String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
                // Los thumbnails de Android suelen ser JPEG
                return "data:image/jpeg;base64," + base64;
            } catch (Exception e) {
                return "";
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        }
        
        // Escape JSON: solo lo mínimo necesario (comillas, backslashes, saltos de línea)
        private String escapeJson(String s) {
            if (s == null) return "";
            StringBuilder r = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') r.append("\\\"");
                else if (c == '\\') r.append("\\\\");
                else if (c == '\n') r.append("\\n");
                else if (c == '\r') r.append("\\r");
                else if (c == '\t') r.append("\\t");
                else if (c < 0x20) r.append(String.format("\\u%04x", (int)c));
                else r.append(c);
            }
            return r.toString();
        }
    }

    // v044: genera un ID unico y estable para este celular.
    // Usa ANDROID_ID (persiste mientras no se resetee de fabrica ni se reinstale limpio).
    private String obtenerDeviceId() {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.isEmpty()) androidId = "SINID";
            // Combinar con el applicationId para que sea unico por app
            String base = androidId + "|" + getPackageName();
            // Hash SHA-256 → tomar los primeros bytes → formato legible EAJFV-XXXX-XXXX-XXXX
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(base.getBytes("UTF-8"));
            String hex = "";
            for (int i = 0; i < 6; i++) {
                hex += String.format("%02X", hash[i]);
            }
            // hex tiene 12 caracteres → EAJFV-XXXX-XXXX-XXXX
            return "EAJFV-" + hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
        } catch (Exception e) {
            return "EAJFV-0000-0000-0000";
        }
    }

    // ═══ v108n: PRUEBA GRATIS — almacenamiento externo persistente ═══
    // Guardamos el timestamp de inicio de prueba en un archivo oculto en la carpeta
    // pública de Descargas. Este archivo NO se borra al desinstalar la app, así que
    // el usuario no puede reiniciar la prueba reinstalando.
    private static final String PRUEBA_FILE = ".eajfv_sys_cfg.dat";  // nombre discreto

    private String leerPruebaExterno() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, PRUEBA_FILE);
            if (!f.exists()) return "";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[64];
            int n = fis.read(buf);
            fis.close();
            if (n <= 0) return "";
            String s = new String(buf, 0, n, "UTF-8").trim();
            // Validar que sea un número (timestamp)
            if (s.matches("\\d{10,16}")) return s;
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private void guardarPruebaExterno(String ts) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, PRUEBA_FILE);
            // Si ya existe, NO sobrescribir (para no reiniciar la prueba)
            if (f.exists()) {
                String existente = leerPruebaExterno();
                if (existente != null && !existente.isEmpty()) return;
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(ts.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {}
    }

    private void guardarEnDescargas(byte[] data, String nombre) throws Exception {
        final File[] archivoGuardado = new File[1];
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, nombre);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(data); os.close();
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, nombre);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data); fos.close();
            archivoGuardado[0] = f;
        }
        // v047: mostrar un dialogo claro con opcion de compartir (en vez de un toast que se pierde)
        final byte[] dataFinal = data;
        runOnUiThread(() -> mostrarDialogoGuardado(nombre, dataFinal));
    }

    // v047: dialogo que confirma el guardado y ofrece compartir directo
    private void mostrarDialogoGuardado(String nombre, byte[] data) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("✓ Guardado en Descargas");
            b.setMessage("El archivo se guardó:\n\n" + nombre + "\n\nLo encuentras en la carpeta Descargas de tu celular. ¿Quieres compartirlo ahora?");
            b.setPositiveButton("Compartir", (dialog, which) -> {
                try {
                    String n = nombre.toLowerCase();
                    compartirArchivo(data, nombre, "", null);
                } catch (Exception e) {
                    toast("No se pudo compartir");
                }
            });
            b.setNegativeButton("Cerrar", null);
            b.setCancelable(true);
            b.show();
        } catch (Exception e) {
            toast("Guardado en Descargas: " + nombre);
        }
    }

    // v041: comparte un archivo usando el AppFileProvider + Intent nativo.
    // Si paquetePreferido no es null (ej "com.whatsapp"), intenta abrir esa app directo.
    private void compartirArchivo(byte[] data, String nombre, String mensaje, String paquetePreferido) throws Exception {
        // 1. Escribir el archivo en filesDir/compartir (carpeta que el AppFileProvider conoce)
        File compartirDir = new File(getFilesDir(), "compartir");
        if (!compartirDir.exists()) compartirDir.mkdirs();
        File archivo = new File(compartirDir, nombre);
        FileOutputStream fos = new FileOutputStream(archivo);
        fos.write(data); fos.close();

        // 2. Obtener el Uri via AppFileProvider
        Uri uri = AppFileProvider.getUriForFile(this, getPackageName() + ".fileprovider", archivo);

        // 3. Determinar el tipo MIME
        String mime = "application/octet-stream";
        String n = nombre.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) mime = "image/jpeg";
        else if (n.endsWith(".png")) mime = "image/png";
        else if (n.endsWith(".pdf")) mime = "application/pdf";

        // 4. Construir el Intent de compartir
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        if (mensaje != null && !mensaje.isEmpty()) {
            share.putExtra(Intent.EXTRA_TEXT, mensaje);
        }
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // v048 FIX: usar variable temporal no-final para evitar el error de compilacion
        // "variable intentFinal might already have been assigned"
        Intent tmp = Intent.createChooser(share, "Compartir " + nombre);
        if (paquetePreferido != null) {
            PackageManager pm = getPackageManager();
            try {
                pm.getPackageInfo(paquetePreferido, 0);
                share.setPackage(paquetePreferido);
                tmp = share;
            } catch (PackageManager.NameNotFoundException e) {
                // la app no esta instalada → dejar el chooser general (ya asignado)
            }
        }
        final Intent intentFinal = tmp;

        runOnUiThread(() -> {
            try {
                startActivity(intentFinal);
            } catch (Exception e) {
                toast("No hay app para compartir: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------ instalacion / update

    private File archivoSistema() { return new File(getFilesDir(), FILE_NAME); }

    private void asegurarSistema() throws Exception {
        File f = archivoSistema();
        // v103 LÓGICA INTELIGENTE:
        //   1. Si es primera vez (no hay archivo) → copia el asset
        //   2. Si el APK fue actualizado (versionCode subió) → copia el asset (build nuevo)
        //   3. Si el HTML instalado tiene versión >= al del asset → respeta el instalado (importado)
        //   4. En cualquier otro caso → copia el asset
        int codigoAPKActual = 1;
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            codigoAPKActual = pi.versionCode;
        } catch (Exception ignored) {}
        int codigoAPKGuardado = prefs.getInt("apk_version_code", 0);
        boolean apkActualizado = codigoAPKActual > codigoAPKGuardado;
        
        boolean copiarAsset = true;  // por defecto copia
        
        if (f.exists() && f.length() > 0 && !apkActualizado) {
            // El APK NO cambió. Verificar si el HTML instalado es más nuevo que el asset.
            try {
                String vInstalada = leerVersion(f);
                String vAsset = leerVersionAsset();
                if (vInstalada != null && vAsset != null) {
                    int nInstalada = extraerNumeroVersion(vInstalada);
                    int nAsset = extraerNumeroVersion(vAsset);
                    // Si el instalado tiene versión >= al asset, RESPETAR el instalado (importado por WhatsApp)
                    if (nInstalada >= nAsset) {
                        copiarAsset = false;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        if (copiarAsset) {
            try {
                InputStream in = getAssets().open(FILE_NAME);
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close(); in.close();
            } catch (Exception e) {
                if (!(f.exists() && f.length() > 0)) throw e;
            }
        }
        prefs.edit()
            .putString("version", leerVersion(f))
            .putInt("apk_version_code", codigoAPKActual)
            .apply();
    }
    
    // v99: Lee SISTEMA_VERSION del asset (sin copiarlo al disco). — mantenido por si acaso.
    private String leerVersionAsset() {
        try (InputStream is = getAssets().open(FILE_NAME)) {
            byte[] buf = new byte[1 << 20];
            StringBuilder cola = new StringBuilder();
            int n;
            int leidos = 0;
            final int MAX_LEIDOS = 2 << 20;  // 2 MB máximo (SISTEMA_VERSION está al inicio)
            while ((n = is.read(buf)) > 0 && leidos < MAX_LEIDOS) {
                cola.append(new String(buf, 0, n, "UTF-8"));
                leidos += n;
                Matcher m = P_VERSION.matcher(cola);
                if (m.find()) return m.group(1);
                if (cola.length() > 200000) cola.delete(0, cola.length() - 50000);
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    // v99: Extrae el número de una versión tipo "v99" o "v89.5" o "v100".
    private int extraerNumeroVersion(String v) {
        if (v == null) return 0;
        Matcher m = P_NUM.matcher(v);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private String versionInstalada() {
        String v = prefs.getString("version", null);
        if (v == null) {
            v = leerVersion(archivoSistema());
            prefs.edit().putString("version", v).apply();
        }
        return v == null ? "?" : v;
    }

    private static final Pattern P_VERSION =
            Pattern.compile("SISTEMA_VERSION\\s*=\\s*['\"]([^'\"]{1,40})['\"]");
    private static final Pattern P_NUM = Pattern.compile("(\\d+)");

    /** Lee const SISTEMA_VERSION del archivo (lee por bloques, no carga todo en RAM). */
    private String leerVersion(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            StringBuilder cola = new StringBuilder();
            int n;
            while ((n = fis.read(buf)) > 0) {
                String chunk = cola + new String(buf, 0, n, "UTF-8");
                Matcher m = P_VERSION.matcher(chunk);
                if (m.find()) return m.group(1);
                cola.setLength(0);
                cola.append(chunk.substring(Math.max(0, chunk.length() - 200)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Convierte "v020" / "v020 MOVIL" en 20 para poder comparar. */
    private int numVersion(String v) {
        if (v == null) return -1;
        Matcher m = P_NUM.matcher(v);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return -1; } }
        return -1;
    }

    private void manejarIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String a = intent.getAction();
        if (Intent.ACTION_VIEW.equals(a)) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(a)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            intent.setData(null);
            intent.removeExtra(Intent.EXTRA_STREAM);
            procesarActualizacion(uri, null);
        }
    }

    /** Copia el uri a un temporal, lee la version y pregunta si actualiza.
     *  v052: detecta si el archivo es un CATALOGO DE PRECIOS (.json) en vez del
     *  sistema completo. Si es de precios, aplica solo los precios sin tocar el sistema. */
    private void procesarActualizacion(final Uri uri, final File archivoDirecto) {
        new Thread(() -> {
            try {
                final File tmp = new File(getCacheDir(), "update.html");
                if (archivoDirecto != null) {
                    copiar(new FileInputStream(archivoDirecto), tmp);
                } else {
                    InputStream in = getContentResolver().openInputStream(uri);
                    copiar(in, tmp);
                }
                if (tmp.length() < 20) { runOnUiThread(() -> toast("Archivo vacio o invalido")); return; }

                // v052: leer el inicio del archivo para ver si es un catalogo de precios
                String muestra = leerInicioArchivo(tmp, 300);
                if (muestra != null && muestra.contains("CATALOGO_PRECIOS_JFV")) {
                    // Es un archivo de PRECIOS → aplicar solo los precios
                    final String contenido = leerArchivoCompleto(tmp);
                    runOnUiThread(() -> confirmarActualizacionPrecios(contenido, tmp));
                    return;
                }

                // Sino, es el sistema completo (HTML) → flujo normal
                if (tmp.length() < 500) { runOnUiThread(() -> toast("Archivo vacio o invalido")); return; }
                final String nueva = leerVersion(tmp);
                if (nueva == null) {
                    runOnUiThread(() -> toast("Ese archivo no parece el Sistema ni un catalogo de precios"));
                    return;
                }
                final String actual = versionInstalada();
                runOnUiThread(() -> confirmarActualizacion(actual, nueva, tmp));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Error leyendo el archivo: " + e.getMessage()));
            }
        }).start();
    }

    // v052: lee los primeros N caracteres de un archivo
    private String leerInicioArchivo(File f, int n) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[n];
            int leidos = in.read(buf);
            in.close();
            if (leidos <= 0) return "";
            return new String(buf, 0, leidos, "UTF-8");
        } catch (Exception e) { return null; }
    }

    // v052: lee un archivo completo a String
    private String leerArchivoCompleto(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    // v052: confirma y aplica un catalogo de precios inyectandolo en el WebView
    private void confirmarActualizacionPrecios(final String contenidoJson, final File tmp) {
        // Intentar extraer la fecha legible del JSON para mostrarla
        String fecha = "recientes";
        try {
            int idx = contenidoJson.indexOf("\"fechaLegible\"");
            if (idx >= 0) {
                int c1 = contenidoJson.indexOf(':', idx);
                int q1 = contenidoJson.indexOf('"', c1 + 1);
                int q2 = contenidoJson.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) fecha = contenidoJson.substring(q1 + 1, q2);
            }
        } catch (Exception e) {}
        final String fechaFinal = fecha;
        new AlertDialog.Builder(this)
                .setTitle("💰 Actualizar precios")
                .setMessage("Recibiste una lista de precios (" + fechaFinal + ").\n\n"
                        + "Se actualizarán TODOS los precios de tu catálogo.\n\n"
                        + "⚠️ Tus desgloses, clientes y demás datos NO se tocan, solo los precios.\n\n"
                        + "¿Actualizar precios ahora?")
                .setPositiveButton("ACTUALIZAR PRECIOS", (d, w) -> aplicarActualizacionPrecios(contenidoJson))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // v052: inyecta los precios en el sistema (WebView) via JavaScript
    private void aplicarActualizacionPrecios(String contenidoJson) {
        try {
            // Codificar el JSON en base64 para pasarlo seguro al JS
            String b64 = Base64.encodeToString(contenidoJson.getBytes("UTF-8"), Base64.NO_WRAP);
            String js = "(function(){try{"
                + "var json=decodeURIComponent(escape(atob('" + b64 + "')));"
                + "if(typeof _aplicarPreciosImportados==='function'){"
                + "  return _aplicarPreciosImportados(json);"
                + "} else { return 'NOFUNC'; }"
                + "}catch(e){return 'ERR:'+e.message;}})();";
            web.evaluateJavascript(js, valor -> {
                String v = valor != null ? valor.replace("\"", "") : "";
                if (v.startsWith("OK")) {
                    toast("✓ Precios actualizados");
                    // Recargar el WebView para que se vean los precios nuevos
                    web.reload();
                } else if (v.startsWith("NOFUNC")) {
                    toast("Tu sistema es viejo. Actualiza primero el sistema.");
                } else {
                    toast("No se pudieron aplicar los precios: " + v);
                }
            });
        } catch (Exception e) {
            toast("Error aplicando precios: " + e.getMessage());
        }
    }

    private void confirmarActualizacion(String actual, final String nueva, final File tmp) {
        int a = numVersion(actual), n = numVersion(nueva);
        String titulo;
        String msg;
        if (n > a) {
            titulo = "Nueva actualizacion disponible";
            msg = "Instalada:  " + actual + "\nNueva:      " + nueva + "\n\nTus datos NO se pierden.\n\nInstalar " + nueva + "?";
        } else if (n == a) {
            titulo = "Misma version";
            msg = "Ya tienes " + actual + ".\n\nReinstalar de todos modos?";
        } else {
            titulo = "Version mas vieja";
            msg = "Instalada: " + actual + "\nArchivo:   " + nueva + "\n\nEs mas vieja. Volver a esa version?";
        }
        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setPositiveButton("ACTUALIZAR", (d, w) -> aplicarActualizacion(tmp, nueva))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void aplicarActualizacion(File tmp, String nueva) {
        try {
            File destino = archivoSistema();
            File respaldo = new File(getFilesDir(), "sistema_anterior.html");
            if (destino.exists()) {
                copiar(new FileInputStream(destino), respaldo);
                prefs.edit().putString("version_anterior", versionInstalada()).apply();
            }
            copiar(new FileInputStream(tmp), destino);
            prefs.edit().putString("version", nueva).apply();
            tmp.delete();
            toast("Actualizado a " + nueva);
            web.clearCache(true);
            cargarSistema();
        } catch (Exception e) {
            toast("Fallo la actualizacion: " + e.getMessage());
        }
    }

    private void revertir() {
        File respaldo = new File(getFilesDir(), "sistema_anterior.html");
        if (!respaldo.exists()) { toast("No hay version anterior guardada"); return; }
        try {
            copiar(new FileInputStream(respaldo), archivoSistema());
            String v = prefs.getString("version_anterior", leerVersion(archivoSistema()));
            prefs.edit().putString("version", v).apply();
            toast("Se volvio a " + v);
            web.clearCache(true);
            cargarSistema();
        } catch (Exception e) { toast("No se pudo revertir"); }
    }

    private void copiar(InputStream in, File destino) throws Exception {
        FileOutputStream out = new FileOutputStream(destino);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close(); in.close();
    }

    // ------------------------------------------------- busqueda automatica

    private static final String[] CARPETAS = {
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Downloads",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "/storage/emulated/0/WhatsApp/Media/WhatsApp Documents",
        "/storage/emulated/0/Documents"
    };

    private boolean tienePermisoArchivos() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermisoArchivos() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALL_FILES);
                toast("Activa 'Permitir acceso para administrar todos los archivos'");
            } catch (Exception e) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_ALL_FILES);
        }
    }

    /** Escanea Descargas / WhatsApp buscando un HTML del sistema mas nuevo. */
    private void buscarActualizacionAuto(final boolean avisarSiNoHay) {
        new Thread(() -> {
            File mejor = null;
            int mejorNum = numVersion(versionInstalada());
            for (String ruta : CARPETAS) {
                File dir = new File(ruta);
                File[] hijos = dir.listFiles();
                if (hijos == null) continue;
                for (File f : hijos) {
                    String n = f.getName().toLowerCase();
                    if (!n.endsWith(".html")) continue;
                    if (!n.contains("sistema")) continue;
                    Matcher m = Pattern.compile("v(\\d{2,4})").matcher(n);
                    if (!m.find()) continue;
                    int num;
                    try { num = Integer.parseInt(m.group(1)); } catch (Exception e) { continue; }
                    if (num > mejorNum) { mejorNum = num; mejor = f; }
                }
            }
            final File encontrado = mejor;
            runOnUiThread(() -> {
                if (encontrado != null) {
                    if (prefs.getString("ignorar", "").equals(encontrado.getName())) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Actualizacion encontrada")
                            .setMessage("Se encontro:\n\n" + encontrado.getName() +
                                    "\n\nInstalada actualmente: " + versionInstalada() + "\n\nRevisar e instalar?")
                            .setPositiveButton("SI", (d, w) -> procesarActualizacion(null, encontrado))
                            .setNegativeButton("Ahora no", null)
                            .setNeutralButton("Ignorar este", (d, w) ->
                                    prefs.edit().putString("ignorar", encontrado.getName()).apply())
                            .show();
                } else if (avisarSiNoHay) {
                    toast("No hay version mas nueva que " + versionInstalada());
                }
            });
        }).start();
    }

    // ------------------------------------------------------------- menu UI

    // v053: crearBotonFlotante() ELIMINADO por completo. El menu se abre
    // manteniendo presionado VOLUMEN ABAJO (~1.5s).

    private void mostrarMenu() {
        final String[] ops = {
            "Buscar actualizacion (Descargas/WhatsApp)",
            "Actualizar desde un archivo...",
            "Recargar sistema",
            "Imprimir / Guardar PDF",
            "Compartir el sistema por WhatsApp",
            "Volver a la version anterior",
            "Informacion"
        };
        new AlertDialog.Builder(this)
            .setTitle("Sistema JFV  \u00b7  " + versionInstalada())
            .setItems(ops, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int i) {
                    switch (i) {
                        case 0:
                            if (!tienePermisoArchivos()) pedirPermisoArchivos();
                            else buscarActualizacionAuto(true);
                            break;
                        case 1: elegirArchivo(); break;
                        case 2: web.clearCache(true); cargarSistema(); break;
                        case 3: imprimir(web, "Sistema"); break;
                        case 4: compartirSistema(); break;
                        case 5: revertir(); break;
                        case 6:
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Informacion")
                                .setMessage("Version del sistema: " + versionInstalada()
                                        + "\nServidor local: 127.0.0.1:" + port
                                        + "\nArchivo: " + archivoSistema().getAbsolutePath()
                                        + "\nTamano: " + (archivoSistema().length() / 1024) + " KB"
                                        + "\n\nPara actualizar: recibe el HTML por WhatsApp,"
                                        + " descargalo y abrelo con esta app, o usa"
                                        + " 'Buscar actualizacion'.")
                                .setPositiveButton("OK", null).show();
                            break;
                    }
                }
            }).show();
    }

    private void elegirArchivo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Escoge el HTML del sistema"), REQ_PICK_UPDATE);
        } catch (Exception e) { toast("No hay explorador de archivos"); }
    }

    private void compartirSistema() {
        try {
            File pub = new File(getFilesDir(), "compartir");
            if (!pub.exists()) pub.mkdirs();
            File out = new File(pub, "Sistema_Desglose_y_Facturacion_" + versionInstalada() + "_MOVIL.html");
            copiar(new FileInputStream(archivoSistema()), out);
            Uri uri = androidx_FileProvider(out);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/html");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Enviar sistema"));
        } catch (Exception e) { toast("No se pudo compartir: " + e.getMessage()); }
    }

    private Uri androidx_FileProvider(File f) {
        return AppFileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE_CHOOSER) {
            if (fileCallback == null) return;
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getDataString() != null) r = new Uri[]{Uri.parse(data.getDataString())};
                else if (data.getClipData() != null) {
                    int c = data.getClipData().getItemCount();
                    r = new Uri[c];
                    for (int i = 0; i < c; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        } else if (req == REQ_PICK_UPDATE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                procesarActualizacion(data.getData(), null);
            }
        } else if (req == REQ_ALL_FILES) {
            if (tienePermisoArchivos()) buscarActualizacionAuto(true);
        }
    }

    // ------------------------------------------------------------- helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
