package io.telloai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The upgrade request carries {@code sdk}, {@code version} and {@code protocol} so the
 * gateway can log which client connected; the user's path and other query params are
 * kept, and the three keys overwrite any the user supplied.
 */
class ClientIdentityQueryTest {

    private static final String AUTH_OK = "{\"type\":\"auth.ok\",\"version\":\"1.0\"}";

    /** Gradle's project version, passed in by the build. */
    private static final String GRADLE_VERSION = System.getProperty("tello.gradleVersion");

    private static Map<String, String> query(String target) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = URI.create(target).getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String connectAndCapture(FakeGateway server, String url) {
        server.onText(msg -> {
            try {
                server.sendText(AUTH_OK);
            } catch (Exception ignored) {
                // test double
            }
        });
        try (TelloClient client = new TelloClient("tello_test_key", url).connectBlocking()) {
            return server.requestTarget();
        }
    }

    private static void assertIdentity(Map<String, String> q) {
        assertEquals("java", q.get("sdk"));
        assertEquals(GRADLE_VERSION, q.get("version"));
        assertEquals(PublicStatus.PROTOCOL_VERSION, q.get("protocol"));
    }

    @Test
    void sdkVersionMatchesGradleVersion() {
        assertEquals(GRADLE_VERSION, TelloClient.SDK_VERSION);
    }

    @Test
    void plainUrlGetsIdentityQuery() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            String target = connectAndCapture(server, server.url());
            assertEquals("/sdk", URI.create(target).getRawPath());
            Map<String, String> q = query(target);
            assertEquals(3, q.size());
            assertIdentity(q);
        }
    }

    @Test
    void existingQueryAndPathAreKeptAndIdentityKeysOverwritten() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            String url = server.url().replace("/sdk", "/edge/sdk")
                    + "?region=a%20b&sdk=custom&version=9.9.9&x=%26y";
            String target = connectAndCapture(server, url);
            assertEquals("/edge/sdk", URI.create(target).getRawPath());
            Map<String, String> q = query(target);
            assertEquals("a b", q.get("region"));
            assertEquals("&y", q.get("x"));
            assertEquals(5, q.size());
            assertIdentity(q);
        }
    }

    @Test
    void defaultUrlGetsIdentityQuery() {
        URI uri = TelloClient.withClientIdentity(ClientConfig.DEFAULT_URL);
        assertEquals("api.telloai.io", uri.getHost());
        assertEquals("/sdk", uri.getRawPath());
        assertIdentity(query(uri.toString()));
    }
}
