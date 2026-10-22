package br.com.verificador.desktop.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Bounded HTTPS downloads from the official AC-Raiz repository. */
final class OfficialCertificateDownload {
    private HttpClient client;

    byte[] get(URI uri, int limit) throws IOException, InterruptedException {
        if (!"https".equals(uri.getScheme()) || !"acraiz.icpbrasil.gov.br".equals(uri.getHost()))
            throw new IOException("Endereço fora do repositório HTTPS oficial da AC-Raiz.");
        if (client == null) {
            try {
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .sslContext(DesktopTls.context()).followRedirects(HttpClient.Redirect.NEVER).build();
            } catch (Exception exception) { throw new IOException("Falha ao preparar a conexão HTTPS com o ITI.", exception); }
        }
        // The predefined repository endpoints return their files directly; do not follow redirects.
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                info -> limitedBody(limit));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " ao baixar " + uri.getPath());
        return response.body();
    }

    private static HttpResponse.BodySubscriber<byte[]> limitedBody(int limit) {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private java.util.concurrent.Flow.Subscription subscription;
            private long size;
            private boolean failed;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(java.util.List<java.nio.ByteBuffer> items) {
                if (failed) return;
                for (var item : items) size += item.remaining();
                if (size > limit) {
                    failed = true; subscription.cancel(); delegate.onError(new IOException("Download excede o tamanho permitido."));
                } else delegate.onNext(items);
            }
            public void onError(Throwable error) { if (!failed) delegate.onError(error); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }
}
