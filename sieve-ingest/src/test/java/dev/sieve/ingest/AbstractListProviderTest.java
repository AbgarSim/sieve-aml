package dev.sieve.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AbstractListProviderTest {

    private final HttpClient client = mock(HttpClient.class);

    @Test
    void shouldRetryWhenDownloadIsCutShort() throws Exception {
        HttpResponse<byte[]> ok = response(200);
        doThrow(new IOException("chunked transfer encoding, state: READING_LENGTH"))
                .doReturn(ok)
                .when(client)
                .send(any(HttpRequest.class), any());

        List<SanctionedEntity> entities = new StubProvider(client).fetch();

        assertThat(entities).isEmpty();
        verify(client, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void shouldGiveUpAfterMaxAttemptsWhenNetworkKeepsFailing() throws Exception {
        when(client.send(any(HttpRequest.class), any())).thenThrow(new IOException("reset"));

        assertThatThrownBy(() -> new StubProvider(client).fetch())
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("Network error");
        verify(client, times(AbstractListProvider.MAX_ATTEMPTS))
                .send(any(HttpRequest.class), any());
    }

    @Test
    void shouldNotRetryWhenServerReturnsErrorStatus() throws Exception {
        HttpResponse<byte[]> notFound = response(404);
        doReturn(notFound).when(client).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> new StubProvider(client).fetch())
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("status=404");
        verify(client, times(1)).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new byte[0]);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        return response;
    }

    private static final class StubProvider extends AbstractListProvider {

        StubProvider(HttpClient client) {
            super(
                    ListSource.OFAC_SDN,
                    URI.create("https://example.test/list.xml"),
                    "*/*",
                    client,
                    Duration.ofSeconds(5));
        }

        @Override
        Duration retryBackoff() {
            return Duration.ZERO;
        }

        @Override
        protected List<SanctionedEntity> parseResponse(byte[] body) {
            return List.of();
        }
    }
}
