package br.com.verificador.desktop.service;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Retains Java trust and also honors the Windows certificate authorities trusted by the user. */
public final class DesktopTls {
    private DesktopTls() { }
    public static SSLContext context() throws Exception {
        // The official repository does not always send all intermediate certificates.
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
        var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
        merged.load(null, null);
        int index = 0;
        for (var manager : defaults.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) for (X509Certificate certificate : x509.getAcceptedIssuers())
                merged.setCertificateEntry("java-" + index++, certificate);
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            KeyStore windows = KeyStore.getInstance("Windows-ROOT");
            windows.load(null, null);
            for (var aliases = windows.aliases(); aliases.hasMoreElements();) {
                var certificate = windows.getCertificate(aliases.nextElement());
                if (certificate != null) merged.setCertificateEntry("windows-" + index++, certificate);
            }
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(merged);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
