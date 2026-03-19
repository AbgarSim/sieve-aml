package dev.sieve.ingest;

import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link HttpClient} instances used by sanctions list providers.
 *
 * <p>Some government sanctions endpoints (notably OFAC) use certificates whose chains are not
 * present in all JDK trust stores (e.g. Amazon Corretto). This factory provides a trust-all SSL
 * context so ingestion works out of the box.
 */
public final class HttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(HttpClientFactory.class);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private HttpClientFactory() {}

    /**
     * Creates an {@link HttpClient} that trusts all TLS certificates.
     *
     * @return a configured HttpClient
     */
    public static HttpClient createTrustAllClient() {
        return createTrustAllClient(DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Creates an {@link HttpClient} that trusts all TLS certificates with a custom connect timeout.
     *
     * @param connectTimeout the connection timeout
     * @return a configured HttpClient
     */
    public static HttpClient createTrustAllClient(Duration connectTimeout) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {TRUST_ALL_MANAGER}, new SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("Failed to create trust-all SSL context, falling back to default", e);
            return HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
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
