package dev.sieve.benchmark;

import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Creates an {@link HttpClient} that trusts all TLS certificates.
 *
 * <p><strong>WARNING:</strong> This is intended exclusively for benchmarking. Some government
 * sanctions endpoints (e.g. OFAC) present certificates that are not in the default Java truststore.
 * This client bypasses certificate validation so downloads succeed regardless.
 *
 * <p>Do NOT use this in production code.
 */
final class TrustAllHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private TrustAllHttpClient() {}

    /**
     * Builds an {@link HttpClient} with a trust-all SSL context.
     *
     * @return a new HttpClient that accepts any server certificate
     */
    static HttpClient create() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, null);

            return HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to create trust-all HTTP client", e);
        }
    }

    private static final X509TrustManager TRUST_ALL_MANAGER =
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
}
