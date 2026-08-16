package com.euroaluminio.reproductor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * Habilita TLS 1.2 en Android viejo (4.x), que por defecto solo usa TLS 1.0.
 * Sin esto, muchas conexiones HTTPS (como iTunes/Apple) fallan.
 */
public class Tls12SocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;
    private static final String[] TLS = { "TLSv1", "TLSv1.1", "TLSv1.2" };

    public Tls12SocketFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        ctx.init(null, tmf.getTrustManagers(), null);
        delegate = ctx.getSocketFactory();
    }

    private Socket habilitar(Socket s) {
        if (s instanceof SSLSocket) {
            try { ((SSLSocket) s).setEnabledProtocols(TLS); } catch (Exception e) {
                try { ((SSLSocket) s).setEnabledProtocols(new String[]{ "TLSv1.2" }); } catch (Exception e2) {}
            }
        }
        return s;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override public Socket createSocket() throws IOException { return habilitar(delegate.createSocket()); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return habilitar(delegate.createSocket(s, host, port, autoClose)); }
    @Override public Socket createSocket(String host, int port) throws IOException, UnknownHostException { return habilitar(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException { return habilitar(delegate.createSocket(host, port, localHost, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return habilitar(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return habilitar(delegate.createSocket(address, port, localAddress, localPort)); }
}
