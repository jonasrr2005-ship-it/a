package de.jonas.sprachassistent;

import android.content.Context;

import org.conscrypt.Conscrypt;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 4.0.4 kann von sich aus kein TLS 1.2 und kennt viele heutige
 * Stammzertifikate nicht. Deshalb: Conscrypt (eigene, moderne TLS-Bibliothek)
 * plus mitgelieferte Stammzertifikate (assets/roots.pem).
 */
public final class Tls {

    private static SSLSocketFactory factory;

    private Tls() {
    }

    public static synchronized SSLSocketFactory factory(Context ctx) throws Exception {
        if (factory != null) return factory;
        Provider provider;
        try {
            provider = Conscrypt.newProvider();
        } catch (Throwable t) {
            throw new Exception("Conscrypt (TLS 1.2) konnte nicht geladen werden: " + t, t);
        }

        // 1) mitgelieferte Stammzertifikate
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        InputStream in = ctx.getAssets().open("roots.pem");
        Collection<? extends Certificate> certs;
        try {
            certs = cf.generateCertificates(in);
        } finally {
            in.close();
        }
        int i = 0;
        for (Certificate c : certs) ks.setCertificateEntry("root" + (i++), c);
        TrustManagerFactory tmfBundled = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmfBundled.init(ks);

        // 2) Zertifikate des Systems
        TrustManagerFactory tmfSystem = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmfSystem.init((KeyStore) null);

        final List<X509TrustManager> managers = new ArrayList<X509TrustManager>();
        addX509(managers, tmfBundled.getTrustManagers());
        addX509(managers, tmfSystem.getTrustManagers());

        X509TrustManager combined = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("nicht unterstuetzt");
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                CertificateException last = null;
                for (X509TrustManager m : managers) {
                    try {
                        m.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException e) {
                        last = e;
                    } catch (RuntimeException e) {
                        last = new CertificateException(e.toString());
                    }
                }
                throw last != null ? last : new CertificateException("Kein Zertifikatspruefer");
            }

            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> all = new ArrayList<X509Certificate>();
                for (X509TrustManager m : managers) {
                    for (X509Certificate c : m.getAcceptedIssuers()) all.add(c);
                }
                return all.toArray(new X509Certificate[all.size()]);
            }
        };

        SSLContext sc = SSLContext.getInstance("TLS", provider);
        sc.init(null, new TrustManager[]{combined}, null);
        factory = new ModernFactory(sc.getSocketFactory());
        AppLog.info("TLS bereit (Conscrypt, " + i + " Stammzertifikate mitgeliefert)");
        return factory;
    }

    private static void addX509(List<X509TrustManager> out, TrustManager[] tms) {
        for (TrustManager t : tms) {
            if (t instanceof X509TrustManager) out.add((X509TrustManager) t);
        }
    }

    /** Erzwingt TLS 1.2 / 1.3 auf jeder Verbindung. */
    private static final class ModernFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ModernFactory(SSLSocketFactory d) {
            this.d = d;
        }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> want = new ArrayList<String>();
                for (String p : ss.getSupportedProtocols()) {
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) want.add(p);
                }
                if (!want.isEmpty()) ss.setEnabledProtocols(want.toArray(new String[want.size()]));
            }
            return s;
        }

        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return fix(d.createSocket(s, host, port, autoClose));
        }

        public Socket createSocket(String host, int port) throws java.io.IOException {
            return fix(d.createSocket(host, port));
        }

        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws java.io.IOException {
            return fix(d.createSocket(host, port, localHost, localPort));
        }

        public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return fix(d.createSocket(host, port));
        }

        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws java.io.IOException {
            return fix(d.createSocket(address, port, localAddress, localPort));
        }
    }
}
