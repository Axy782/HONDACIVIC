package com.sistemadesglose.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP minimo que corre solo en 127.0.0.1.
 * Se usa para que el WebView cargue el sistema desde un ORIGEN FIJO
 * (http://127.0.0.1:PUERTO) y asi el localStorage NUNCA se pierda
 * cuando se actualiza el archivo HTML.
 */
public class LocalServer {

    private final File root;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private int port = -1;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public LocalServer(File root) {
        this.root = root;
    }

    public int getPort() {
        return port;
    }

    /** Intenta abrir un puerto fijo; si esta ocupado prueba los siguientes. */
    public int start(int preferredPort) throws IOException {
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 20; p++) {
            try {
                serverSocket = new ServerSocket(p, 64, InetAddress.getByName("127.0.0.1"));
                port = p;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;

        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket s = serverSocket.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                    } catch (Exception e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return port;
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket socket) {
        InputStream in = null;
        OutputStream out = null;
        try {
            socket.setSoTimeout(20000);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
            String requestLine = reader.readLine();
            if (requestLine == null) { socket.close(); return; }

            // consumir headers
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) { /* skip */ }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { send(out, 400, "text/plain", "Bad Request".getBytes("UTF-8")); socket.close(); return; }

            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            path = URLDecoder.decode(path, "UTF-8");
            if (path.equals("/") || path.isEmpty()) path = "/" + MainActivity.FILE_NAME;

            // Anti path traversal
            File target = new File(root, path).getCanonicalFile();
            if (!target.getPath().startsWith(root.getCanonicalFile().getPath())) {
                send(out, 403, "text/plain", "Forbidden".getBytes("UTF-8"));
                socket.close();
                return;
            }
            if (!target.exists() || target.isDirectory()) {
                send(out, 404, "text/plain", ("No encontrado: " + path).getBytes("UTF-8"));
                socket.close();
                return;
            }

            sendFile(out, target);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { if (out != null) out.flush(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sendFile(OutputStream out, File f) throws IOException {
        String ct = mime(f.getName());
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(ct).append("\r\n");
        h.append("Content-Length: ").append(f.length()).append("\r\n");
        h.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));

        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        fis.close();
        out.flush();
    }

    private void send(OutputStream out, int code, String ct, byte[] body) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(ct).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
