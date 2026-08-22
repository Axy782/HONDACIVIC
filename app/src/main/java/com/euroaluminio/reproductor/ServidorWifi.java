package com.euroaluminio.reproductor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

// Servidor web muy simple para recibir canciones desde el celular por WiFi.
// El celular abre http://IP:8080 , elige archivos y se suben a la carpeta "Descarga".
public class ServidorWifi {

    public interface Callback { void archivoRecibido(String nombre); }

    private final File dir;
    private final Callback cb;
    private ServerSocket server;
    private volatile boolean corriendo = false;
    public final int puerto = 8080;

    public ServidorWifi(File dir, Callback cb) { this.dir = dir; this.cb = cb; }

    public void iniciar() throws Exception {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(puerto));
        corriendo = true;
        new Thread(new Runnable() { public void run() { bucle(); } }).start();
    }

    public void detener() {
        corriendo = false;
        try { if (server != null) server.close(); } catch (Exception e) {}
    }

    public boolean activo() { return corriendo; }

    private void bucle() {
        while (corriendo) {
            try {
                final Socket s = server.accept();
                new Thread(new Runnable() { public void run() { atender(s); } }).start();
            } catch (Exception e) { if (!corriendo) break; }
        }
    }

    private void atender(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String linea = leerLinea(in);
            if (linea == null) { s.close(); return; }
            String[] partes = linea.split(" ");
            String metodo = partes.length > 0 ? partes[0] : "";
            String ruta = partes.length > 1 ? partes[1] : "/";

            long contentLength = 0;
            String l;
            while ((l = leerLinea(in)) != null && l.length() > 0) {
                int c = l.indexOf(':');
                if (c > 0) {
                    String k = l.substring(0, c).trim().toLowerCase();
                    String v = l.substring(c + 1).trim();
                    if (k.equals("content-length")) { try { contentLength = Long.parseLong(v); } catch (Exception e) {} }
                }
            }

            if (metodo.equals("POST") && ruta.startsWith("/subir")) {
                String nombre = "cancion.mp3";
                int q = ruta.indexOf("nombre=");
                if (q >= 0) { nombre = urldecode(ruta.substring(q + 7)); }
                nombre = limpiarNombreArchivo(nombre);
                boolean ok = false;
                try {
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, nombre);
                    FileOutputStream fos = new FileOutputStream(dest);
                    byte[] buf = new byte[65536];
                    long falta = contentLength; int n;
                    while (falta > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, falta))) > 0) {
                        fos.write(buf, 0, n); falta -= n;
                    }
                    fos.flush(); fos.close();
                    ok = true;
                    if (cb != null) cb.archivoRecibido(nombre);
                } catch (Throwable t) { ok = false; }
                responder(out, ok ? "200 OK" : "500 Error", "text/plain; charset=utf-8", ok ? "OK" : "ERROR");
            } else {
                responder(out, "200 OK", "text/html; charset=utf-8", paginaHtml());
            }
            out.flush();
            s.close();
        } catch (Exception e) { try { s.close(); } catch (Exception e2) {} }
    }

    private String leerLinea(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c; boolean algo = false;
        while ((c = in.read()) != -1) {
            algo = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (!algo && sb.length() == 0) return null;
        return sb.toString();
    }

    private void responder(OutputStream out, String estado, String tipo, String cuerpo) throws Exception {
        byte[] b = cuerpo.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(estado).append("\r\n");
        h.append("Content-Type: ").append(tipo).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.write(b);
    }

    private String urldecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // Quita caracteres peligrosos del nombre (evita salir de la carpeta)
    private String limpiarNombreArchivo(String n) {
        if (n == null) return "cancion.mp3";
        n = n.replace("\\", "/");
        int barra = n.lastIndexOf('/');
        if (barra >= 0) n = n.substring(barra + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.length() == 0) n = "cancion.mp3";
        return n;
    }

    private String paginaHtml() {
        return "<!DOCTYPE html><html lang='es'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>Enviar al radio</title><style>"
            + "*{box-sizing:border-box;font-family:Arial,Helvetica,sans-serif}"
            + "body{margin:0;background:#0d0d12;color:#f4f4f8;padding:22px}"
            + "h1{font-size:22px;margin:0 0 4px}p{color:#9a9aa8;margin:0 0 18px;font-size:14px}"
            + ".btn{display:block;width:100%;background:#FFB020;color:#1a1a1a;border:none;border-radius:14px;"
            + "padding:18px;font-size:18px;font-weight:800;margin:10px 0;cursor:pointer}"
            + ".card{background:#1c1c26;border-radius:14px;padding:16px;margin-top:14px}"
            + ".fila{padding:10px 0;border-bottom:1px solid #2a2a36;font-size:14px}"
            + ".ok{color:#4ecb7a}.err{color:#ff6b6b}.prog{color:#7fb0ff}"
            + "</style></head><body>"
            + "<h1>Enviar al radio</h1>"
            + "<p>Elige canciones (MP3) o videos (MP4) de tu celular y se copiaran a la carpeta Descarga del radio.</p>"
            + "<input id='f' type='file' accept='audio/*,video/*,.mp3,.mp4,.3gp,.m4v,.mkv,.avi' multiple style='display:none'>"
            + "<button class='btn' onclick=\"document.getElementById('f').click()\">+ Elegir canciones o videos</button>"
            + "<div id='lista' class='card' style='display:none'></div>"
            + "<script>"
            + "var f=document.getElementById('f'),lista=document.getElementById('lista');"
            + "f.onchange=function(){var fs=f.files;if(!fs.length)return;lista.style.display='block';lista.innerHTML='';subir(fs,0);};"
            + "function subir(fs,i){if(i>=fs.length){var d=document.createElement('div');d.className='fila ok';d.textContent='Listo. '+fs.length+' cancion(es) enviada(s).';lista.appendChild(d);return;}"
            + "var file=fs[i];var row=document.createElement('div');row.className='fila prog';row.textContent='Enviando: '+file.name+' ...';lista.appendChild(row);"
            + "var xhr=new XMLHttpRequest();xhr.open('POST','/subir?nombre='+encodeURIComponent(file.name));"
            + "xhr.upload.onprogress=function(e){if(e.lengthComputable){var p=Math.round(e.loaded/e.total*100);row.textContent='Enviando: '+file.name+' '+p+'%';}};"
            + "xhr.onload=function(){if(xhr.status==200){row.className='fila ok';row.textContent='OK: '+file.name;}else{row.className='fila err';row.textContent='Error: '+file.name;}subir(fs,i+1);};"
            + "xhr.onerror=function(){row.className='fila err';row.textContent='Error: '+file.name;subir(fs,i+1);};"
            + "xhr.send(file);}"
            + "</script></body></html>";
    }
}
