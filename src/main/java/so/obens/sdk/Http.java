package so.obens.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** HttpClient wrapper: api key header, JSON, timeout, and retries with backoff on transient failures. */
final class Http {
    private static final Set<Integer> RETRYABLE = Set.of(408, 425, 429, 500, 502, 503, 504);

    final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final String accountId;

    Http(String baseUrl, String apiKey, Duration timeout, int maxRetries, Duration retryDelay, String accountId) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl is required");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey is required");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.accountId = accountId;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    <T> T request(String method, String requestPath, Object body, TypeReference<T> type) {
        // a delegated account to act on, instead of the key's own
        var path = accountId == null ? requestPath
                : requestPath + (requestPath.contains("?") ? "&" : "?") + "account-id=" + Resource.encode(accountId);
        try {
            var payload = body == null ? null : json.writeValueAsString(body);
            for (int attempt = 0; ; attempt++) {
                var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .header("X-API-KEY", apiKey)
                        .header("Accept", "application/json");
                if (payload != null) builder.header("Content-Type", "application/json");
                builder.method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));

                HttpResponse<String> response;
                try {
                    response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    // network error or timeout: safe to repeat because every call is an upsert
                    if (attempt >= maxRetries) throw e;
                    sleep(delay(attempt, null));
                    continue;
                }
                var status = response.statusCode();
                if (status >= 200 && status < 300) {
                    var text = response.body();
                    return text == null || text.isBlank() ? null : json.readValue(text, type);
                }
                if (RETRYABLE.contains(status) && attempt < maxRetries) {
                    sleep(delay(attempt, response.headers().firstValue("Retry-After").orElse(null)));
                    continue;
                }
                throw new ObensApiException(status, response.body(), method, path);
            }
        } catch (IOException e) {
            throw new ObensException("Request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObensException("Interrupted: " + method + " " + path, e);
        }
    }

    private Duration delay(int attempt, String retryAfter) {
        try {
            if (retryAfter != null && Double.parseDouble(retryAfter) > 0)
                return Duration.ofMillis((long) (Double.parseDouble(retryAfter) * 1000));
        } catch (NumberFormatException ignored) {
            // fall back to the backoff
        }
        return retryDelay.multipliedBy(1L << attempt);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
