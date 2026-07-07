package ai.tello;

/**
 * Connection settings for {@link TelloClient}.
 *
 * <p>{@code apiKey} is sent as {@code Authorization: Bearer <apiKey>} on the WS
 * upgrade request. {@code url} is the gateway {@code /sdk} endpoint.
 */
public final class ClientConfig {

    public static final String DEFAULT_URL = "ws://localhost:3000/sdk";
    public static final String ENV_API_KEY = "TELLO_API_KEY";
    public static final String ENV_URL = "TELLO_URL";

    private final String apiKey;
    private final String url;
    private final long connectTimeoutMillis;

    public ClientConfig(String apiKey, String url) {
        this(apiKey, url, 10_000L);
    }

    public ClientConfig(String apiKey, String url, long connectTimeoutMillis) {
        this.apiKey = apiKey;
        this.url = url;
        this.connectTimeoutMillis = connectTimeoutMillis;
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
}
