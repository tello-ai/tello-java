package io.telloai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.telloai.errors.TelloApiConnectionException;
import io.telloai.errors.TelloApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Client for the Tello public REST API ({@code /v1}): tools, agents, crews, crew
 * generation and call drafts. It is separate from the realtime {@link TelloClient}
 * and shares only the API key.
 *
 * <pre>{@code
 * TelloApi api = new TelloApi();                     // $TELLO_API_KEY, $TELLO_API_URL
 * JsonObject agents = api.agents().list().join();
 * }</pre>
 *
 * <p>Every method returns a {@link CompletableFuture}. Requests and responses are Gson
 * {@link JsonObject}s, so fields this SDK does not know yet are kept. A failed request
 * completes the future with {@link TelloApiException} (the server answered with an error
 * or an unexpected response) or {@link TelloApiConnectionException} (no response). Bad
 * arguments (an empty id, an invalid idempotency key) throw
 * {@link IllegalArgumentException} at once, without a request.
 *
 * <p>Nothing is retried automatically. Writes go live in the account at once.
 */
public final class TelloApi {

    public static final String DEFAULT_API_URL = "https://api.telloai.io/v1";
    public static final String ENV_API_URL = "TELLO_API_URL";
    /** Timeout of reads (get and list). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Timeout of every write, which the service may take long to answer: tool, agent and
     * crew create, update and delete, crew generation, and call draft create, revise and
     * confirm.
     */
    public static final Duration DEFAULT_LONG_TIMEOUT = Duration.ofSeconds(120);

    static final String USER_AGENT = "tello-java-sdk/" + TelloClient.SDK_VERSION;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String apiUrl;
    private final Duration timeout;
    private final Duration longTimeout;
    private final HttpClient http;
    private final Tools tools = new Tools(this);
    private final Agents agents = new Agents(this);
    private final Crews crews = new Crews(this);
    private final CrewGenerations crewGenerations = new CrewGenerations(this);
    private final CallDrafts callDrafts = new CallDrafts(this);

    /** A client with the key from {@code $TELLO_API_KEY} and the URL from {@code $TELLO_API_URL} or the default. */
    public TelloApi() {
        this(builder());
    }

    /** A client with {@code apiKey} (or {@code $TELLO_API_KEY} when null). */
    public TelloApi(String apiKey) {
        this(builder().apiKey(apiKey));
    }

    /** A client with {@code apiKey} and {@code apiUrl}; null falls back to the environment, then the default. */
    public TelloApi(String apiKey, String apiUrl) {
        this(builder().apiKey(apiKey).apiUrl(apiUrl));
    }

    private TelloApi(Builder b) {
        String key = b.apiKey != null ? b.apiKey : b.env.apply(ClientConfig.ENV_API_KEY);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "api_key is required (pass apiKey or set $" + ClientConfig.ENV_API_KEY + ")");
        }
        String url = b.apiUrl != null ? b.apiUrl : b.env.apply(ENV_API_URL);
        if (url == null || url.isEmpty()) {
            url = DEFAULT_API_URL;
        }
        this.apiKey = key;
        this.apiUrl = normalizeUrl(url);
        this.timeout = positive(b.timeout, "timeout");
        this.longTimeout = positive(b.longTimeout, "longTimeout");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The base URL requests go to, without a trailing {@code /}. */
    public String apiUrl() {
        return apiUrl;
    }

    /** Tools ({@code /tools}) agents can call during a call. */
    public Tools tools() {
        return tools;
    }

    /** Agents ({@code /agents}): list them, and read, create, change or delete one. */
    public Agents agents() {
        return agents;
    }

    /** Crews ({@code /crews}): list them, read, create, change or delete one, or generate one from a goal. */
    public Crews crews() {
        return crews;
    }

    /** Crew generation jobs ({@code /crew-generations}) started by {@link Crews#generate(JsonObject)}. */
    public CrewGenerations crewGenerations() {
        return crewGenerations;
    }

    /**
     * Call drafts ({@code /call-drafts}): describe a call in words, review the draft, and
     * confirm it. <b>Confirming places a real, billed call.</b>
     */
    public CallDrafts callDrafts() {
        return callDrafts;
    }

    Duration timeout() {
        return timeout;
    }

    Duration longTimeout() {
        return longTimeout;
    }

    @Override
    public String toString() {
        return "TelloApi[apiUrl=" + apiUrl + "]";
    }

    // -- settings -------------------------------------------------------------

    private static Duration positive(Duration d, String name) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    /** Only https, or plain http to a loopback host (local testing). Trailing slashes are dropped. */
    private static String normalizeUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid API URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        boolean ok = host != null && (scheme.equals("https")
                || (scheme.equals("http") && (host.equals("localhost") || host.equals("127.0.0.1")
                        || host.equals("[::1]"))));
        if (!ok) {
            throw new IllegalArgumentException(
                    "API URL must be https (plain http only for localhost): " + url);
        }
        String normalized = url;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // -- arguments ------------------------------------------------------------

    /** Percent-encodes one path segment; an empty or null id is an argument error. */
    static String segment(String id, String name) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 1–255 printable ASCII characters (0x20–0x7E) without a comma; null means none. */
    static void checkIdempotencyKey(String key) {
        if (key == null) {
            return;
        }
        if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be 1-255 characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ',' || c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException("idempotencyKey must be printable ASCII without commas");
            }
        }
    }

    // -- transport ------------------------------------------------------------

    /**
     * Sends one request. {@code success} are the statuses the API documents for it;
     * {@code 204} completes with null, any other success with the response object.
     */
    CompletableFuture<JsonObject> request(String method, String path, String query, JsonObject body,
                                          String idempotencyKey, Duration timeout, int... success) {
        checkIdempotencyKey(idempotencyKey);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl + path + (query == null ? "" : "?" + query)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        String what = method + " " + path;
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause() : failure;
                        throw new TelloApiConnectionException(connectionMessage(method, what, cause),
                                idempotencyKey, cause);
                    }
                    return interpret(what, response.statusCode(), response.body(), idempotencyKey, success);
                });
    }

    private static String connectionMessage(String method, String what, Throwable cause) {
        String message = what + " failed: " + cause;
        if (!method.equals("GET")) {
            message += "; the change may already have been applied, check it before retrying"
                    + " (retry with the same idempotency key)";
        }
        return message;
    }

    private static JsonObject interpret(String what, int status, byte[] raw, String idempotencyKey, int[] success) {
        if (status < 200 || status > 299) {
            throw apiError(status, raw, idempotencyKey);
        }
        boolean expected = false;
        for (int s : success) {
            expected |= s == status;
        }
        if (!expected) {
            throw invalidResponse(status, "unexpected status " + status + " for " + what, idempotencyKey);
        }
        if (status == 204) {
            return null;
        }
        JsonElement parsed = parse(raw);
        if (parsed == null || !parsed.isJsonObject()) {
            throw invalidResponse(status, what + ": response is not a JSON object", idempotencyKey);
        }
        return parsed.getAsJsonObject();
    }

    static TelloApiException invalidResponse(int status, String message, String idempotencyKey) {
        return new TelloApiException(status, "invalid_response", message, null, idempotencyKey);
    }

    private static TelloApiException apiError(int status, byte[] raw, String idempotencyKey) {
        JsonElement parsed = parse(raw);
        if (parsed != null && parsed.isJsonObject()) {
            JsonObject envelope = parsed.getAsJsonObject();
            String code = string(envelope.get("error"));
            if (code != null && !code.isEmpty()) {
                String message = message(envelope.get("message"));
                JsonElement data = envelope.get("data");
                return new TelloApiException(status, code, message != null ? message : code,
                        data != null && data.isJsonObject() ? data.getAsJsonObject() : null, idempotencyKey);
            }
        }
        return new TelloApiException(status, "http_" + status, reasonPhrase(status), null, idempotencyKey);
    }

    private static JsonElement parse(byte[] raw) {
        try {
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /** The envelope message is a string, or a list of strings for some validation errors. */
    private static String message(JsonElement e) {
        String s = string(e);
        if (s != null || e == null || !e.isJsonArray()) {
            return s == null || s.isEmpty() ? null : s;
        }
        StringBuilder joined = new StringBuilder();
        for (JsonElement item : (JsonArray) e) {
            String part = string(item);
            if (part != null) {
                joined.append(joined.length() == 0 ? "" : "; ").append(part);
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(303, "See Other"),
            Map.entry(304, "Not Modified"),
            Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"),
            Map.entry(411, "Length Required"),
            Map.entry(413, "Content Too Large"),
            Map.entry(414, "URI Too Long"),
            Map.entry(415, "Unsupported Media Type"),
            Map.entry(422, "Unprocessable Content"),
            Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private static String reasonPhrase(int status) {
        return REASONS.getOrDefault(status, "HTTP " + status);
    }

    /** Settings of a {@link TelloApi}; unset values come from the environment or the defaults. */
    public static final class Builder {
        private String apiKey;
        private String apiUrl;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration longTimeout = DEFAULT_LONG_TIMEOUT;
        private Function<String, String> env = System::getenv;

        private Builder() {
        }

        /** The API key; when null, {@code $TELLO_API_KEY}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * The base URL, e.g. {@code https://api.telloai.io/v1}; when null,
         * {@code $TELLO_API_URL}, then {@link #DEFAULT_API_URL}. Must be https; plain
         * http is allowed only for localhost, 127.0.0.1 and ::1.
         */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        /** Timeout of reads, get and list (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Timeout of writes: tool, agent and crew create/update/delete, crew generation,
         * and call draft create/revise/confirm (default 120 seconds).
         */
        public Builder longTimeout(Duration timeout) {
            this.longTimeout = timeout;
            return this;
        }

        /** Environment lookup; tests pass their own so the shell's variables never leak in. */
        Builder env(Function<String, String> env) {
            this.env = env;
            return this;
        }

        public TelloApi build() {
            return new TelloApi(this);
        }
    }
}
