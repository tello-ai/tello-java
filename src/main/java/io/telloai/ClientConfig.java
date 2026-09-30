package io.telloai;

/**
 * Connection settings for {@link TelloClient}.
 *
 * <p>{@code apiKey} is sent only inside the {@code auth} handshake frame's
 * {@code token} field (the first application frame after the socket opens) — never
 * as an upgrade header or a URL query parameter. {@code url} is the gateway {@code /sdk}
 * endpoint. {@code connectTimeoutMillis} bounds both the socket open and the wait
 * for the server's {@code auth.ok}.
 */
public final class ClientConfig {

    public static final String DEFAULT_URL = "ws://localhost:3000/sdk";
    public static final String ENV_API_KEY = "TELLO_API_KEY";
    public static final String ENV_URL = "TELLO_URL";

    private final String apiKey;
    private final String url;
    private final long connectTimeoutMillis;
    private final String authRequestId;

    public ClientConfig(String apiKey, String url) {
        this(apiKey, url, 10_000L);
    }

    public ClientConfig(String apiKey, String url, long connectTimeoutMillis) {
        this(apiKey, url, connectTimeoutMillis, null);
    }

    public ClientConfig(String apiKey, String url, long connectTimeoutMillis, String authRequestId) {
        this.apiKey = apiKey;
        this.url = url;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.authRequestId = authRequestId;
    }

    public String apiKey() {
        return apiKey;
    }

    public String url() {
        return url;
    }

    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /** Optional {@code requestId} echoed by the server in its {@code auth.ok}; may be null. */
    public String authRequestId() {
        return authRequestId;
    }
}
