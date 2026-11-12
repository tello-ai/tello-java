package io.telloai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.telloai.errors.TelloApiConnectionException;
import io.telloai.errors.TelloApiException;
import io.telloai.errors.TelloException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Headers, status checks, error mapping and settings shared by every {@link TelloApi} request. */
class TelloApiTransportTest {

    private static final String KEY = "tello_test_secret_key";
    private static final Function<String, String> NO_ENV = name -> null;

    private FakeApi fake;
    private TelloApi api;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeApi();
        api = TelloApi.builder().apiKey(KEY).apiUrl(fake.url()).build();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    static Throwable failure(CompletableFuture<?> future) {
        CompletionException e = assertThrows(CompletionException.class, future::join);
        return e.getCause();
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // -- headers ------------------------------------------------------------

    @Test
    void sendsBearerKeyAcceptAndSdkUserAgentWithoutBodyHeaders() throws Exception {
        fake.reply(200, "{}");
        api.request("GET", "/tools", null, null, null, api.timeout(), 200).join();

        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals("/v1/tools", r.path());
        assertEquals("Bearer " + KEY, r.header("Authorization"));
        assertEquals("application/json", r.header("Accept"));
        assertEquals("tello-java-sdk/" + System.getProperty("tello.gradleVersion"), r.header("User-Agent"));
        assertNull(r.header("Content-Type"));
        assertNull(r.header("Idempotency-Key"));
        assertEquals("", r.body());
    }

    @Test
    void sendsJsonBodyWithContentTypeAndTheGivenIdempotencyKey() throws Exception {
        fake.reply(201, "{}");
        api.request("POST", "/tools", null, json("{\"name\":\"x\"}"), "key-1", api.timeout(), 201).join();

        FakeApi.Request r = fake.take();
        assertEquals("application/json", r.header("Content-Type"));
        assertEquals("key-1", r.header("Idempotency-Key"));
        assertEquals(json("{\"name\":\"x\"}"), json(r.body()));
    }

    @Test
    void rejectsAnInvalidIdempotencyKeyWithoutARequest() {
        for (String bad : new String[] {"", "a,b", "a\nb", "a\u0001b", "a\u007fb", "café", "키", "x".repeat(256)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> api.request("POST", "/tools", null, new JsonObject(), bad, api.timeout(), 201));
        }
        api.request("POST", "/tools", null, new JsonObject(), "x".repeat(255), api.timeout(), 201)
                .exceptionally(e -> null).join();
        assertEquals(1, fake.requestCount());
    }

    // -- success ------------------------------------------------------------

    @Test
    void returnsTheResponseObjectWithUnknownFieldsKept() {
        fake.reply(200, "{\"id\":\"t_1\",\"brandNew\":{\"x\":[1,2]}}");
        JsonObject out = api.request("GET", "/tools/t_1", null, null, null, api.timeout(), 200).join();
        assertEquals(json("{\"id\":\"t_1\",\"brandNew\":{\"x\":[1,2]}}"), out);
    }

    @Test
    void returnsNullForAnExpectedNoContent() {
        fake.reply(204, "");
        assertNull(api.request("DELETE", "/tools/t_1", null, null, null, api.timeout(), 204).join());
    }

    @Test
    void failsWithInvalidResponseWhenTheSuccessStatusDiffers() {
        fake.reply(200, "{\"id\":\"t_1\"}");
        Throwable e = failure(api.request("POST", "/tools", null, new JsonObject(), null, api.timeout(), 201));
        TelloApiException err = assertInstanceOf(TelloApiException.class, e);
        assertEquals("invalid_response", err.code);
        assertEquals(200, err.status);
    }

    @Test
    void failsWithInvalidResponseWhenTheBodyIsNotAJsonObject() {
        fake.reply(200, "[1,2]");
        fake.replyRaw(200, "<html>ok</html>", "text/html");
        for (int i = 0; i < 2; i++) {
            Throwable e = failure(api.request("GET", "/tools", null, null, null, api.timeout(), 200));
            assertEquals("invalid_response", assertInstanceOf(TelloApiException.class, e).code);
        }
    }

    // -- errors ---------------------------------------------------------------

    @Test
    void mapsTheErrorEnvelopeToTelloApiException() {
        fake.reply(400, "{\"statusCode\":400,\"message\":\"Missing variables\","
                + "\"error\":\"missing_variables\",\"data\":{\"missingVariables\":[\"name\"]}}");
        Throwable e = failure(api.request("POST", "/call-drafts", null, new JsonObject(), "k-9", api.timeout(), 201));
        TelloApiException err = assertInstanceOf(TelloApiException.class, e);
        assertInstanceOf(TelloException.class, err);
        assertEquals(400, err.status);
        assertEquals("missing_variables", err.code);
        assertEquals("Missing variables", err.getMessage());
        assertEquals(json("{\"missingVariables\":[\"name\"]}"), err.data);
        assertEquals("k-9", err.idempotencyKey);
    }

    @Test
    void leavesDataAndIdempotencyKeyNullWhenAbsent() {
        fake.reply(404, "{\"statusCode\":404,\"message\":\"Tool not found\",\"error\":\"tool_not_found\"}");
        TelloApiException err = assertInstanceOf(TelloApiException.class,
                failure(api.request("GET", "/tools/x", null, null, null, api.timeout(), 200)));
        assertEquals("tool_not_found", err.code);
        assertNull(err.data);
        assertNull(err.idempotencyKey);
    }

    @Test
    void mapsANonJsonErrorToHttpStatusCode() {
        fake.replyRaw(502, "<html>bad gateway</html>", "text/html");
        TelloApiException err = assertInstanceOf(TelloApiException.class,
                failure(api.request("GET", "/tools", null, null, null, api.timeout(), 200)));
        assertEquals(502, err.status);
        assertEquals("http_502", err.code);
        assertEquals("Bad Gateway", err.getMessage());
        assertNull(err.data);
    }

    @Test
    void mapsAJsonErrorWithoutAnEnvelopeToHttpStatusCode() {
        fake.reply(403, "{\"message\":\"forbidden by proxy\"}");
        TelloApiException err = assertInstanceOf(TelloApiException.class,
                failure(api.request("GET", "/tools", null, null, null, api.timeout(), 200)));
        assertEquals("http_403", err.code);
        assertEquals("Forbidden", err.getMessage());
        assertNull(err.data);
    }

    @Test
    void reportsATransportFailureAsAConnectionExceptionWithItsKeyAndCause() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        TelloApi dead = TelloApi.builder().apiKey(KEY).apiUrl("http://127.0.0.1:" + port + "/v1").build();
        Throwable e = failure(dead.request("POST", "/tools", null, new JsonObject(), "k-1", dead.timeout(), 201));
        TelloApiConnectionException err = assertInstanceOf(TelloApiConnectionException.class, e);
        assertInstanceOf(TelloException.class, err);
        assertEquals("k-1", err.idempotencyKey);
        assertNotNull(err.getCause());
        assertFalse(err.getMessage().contains(KEY));
    }

    @Test
    void reportsATimeoutAsAConnectionException() {
        TelloApi quick = TelloApi.builder().apiKey(KEY).apiUrl(fake.url())
                .timeout(Duration.ofMillis(200)).build();
        fake.replyAfter(2_000, 200, "{}");
        Throwable e = failure(quick.request("GET", "/tools", null, null, null, quick.timeout(), 200));
        TelloApiConnectionException err = assertInstanceOf(TelloApiConnectionException.class, e);
        assertInstanceOf(HttpTimeoutException.class, err.getCause());
    }

    // -- settings -------------------------------------------------------------

    @Test
    void usesThirtyAndOneHundredTwentySecondTimeoutsByDefault() {
        assertEquals(Duration.ofSeconds(30), api.timeout());
        assertEquals(Duration.ofSeconds(120), api.longTimeout());
        TelloApi custom = TelloApi.builder().apiKey(KEY).apiUrl(fake.url())
                .timeout(Duration.ofSeconds(5)).longTimeout(Duration.ofSeconds(9)).build();
        assertEquals(Duration.ofSeconds(5), custom.timeout());
        assertEquals(Duration.ofSeconds(9), custom.longTimeout());
    }

    @Test
    void apiKeyArgumentWinsOverTelloApiKey() throws Exception {
        Map<String, String> env = Map.of("TELLO_API_KEY", "env_key");
        TelloApi fromEnv = TelloApi.builder().apiUrl(fake.url()).env(env::get).build();
        TelloApi fromArg = TelloApi.builder().apiKey(KEY).apiUrl(fake.url()).env(env::get).build();
        fake.reply(200, "{}").reply(200, "{}");
        fromEnv.request("GET", "/agents", null, null, null, fromEnv.timeout(), 200).join();
        fromArg.request("GET", "/agents", null, null, null, fromArg.timeout(), 200).join();
        assertEquals("Bearer env_key", fake.take().header("Authorization"));
        assertEquals("Bearer " + KEY, fake.take().header("Authorization"));
    }

    @Test
    void requiresAnApiKeyLikeTheRealtimeClient() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TelloApi.builder().env(NO_ENV).build());
        assertEquals("api_key is required (pass apiKey or set $TELLO_API_KEY)", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TelloApi.builder().apiKey("").env(NO_ENV).build());
    }

    @Test
    void resolvesTheApiUrlFromArgumentThenTelloApiUrlThenDefault() {
        Map<String, String> env = Map.of("TELLO_API_URL", "https://staging.example.com/v1/");
        assertEquals("https://api.telloai.io/v1", TelloApi.builder().apiKey(KEY).env(NO_ENV).build().apiUrl());
        assertEquals("https://staging.example.com/v1", TelloApi.builder().apiKey(KEY).env(env::get).build().apiUrl());
        assertEquals("https://other.example.com/v1",
                TelloApi.builder().apiKey(KEY).apiUrl("https://other.example.com/v1//").env(env::get).build().apiUrl());
    }

    @Test
    void allowsPlainHttpOnlyForLoopbackHosts() {
        for (String ok : new String[] {"http://127.0.0.1:8080/v1", "http://localhost/v1", "http://[::1]:9/v1"}) {
            assertEquals(ok, TelloApi.builder().apiKey(KEY).apiUrl(ok).env(NO_ENV).build().apiUrl());
        }
        for (String bad : new String[] {"http://api.telloai.io/v1", "ftp://localhost/v1", "not a url", "https:///v1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TelloApi.builder().apiKey(KEY).apiUrl(bad).env(NO_ENV).build(), bad);
        }
    }

    @Test
    void neverShowsTheKeyInToString() {
        String text = api.toString();
        assertFalse(text.contains(KEY));
        assertTrue(text.contains(fake.url()));
    }
}
