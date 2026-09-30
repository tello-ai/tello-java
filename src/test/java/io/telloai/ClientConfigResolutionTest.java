package io.telloai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Which gateway URL a {@link TelloClient} constructor resolves. Each test passes its
 * own environment, so a {@code TELLO_URL} set in the shell running the tests never
 * leaks in.
 */
class ClientConfigResolutionTest {

    private static final String API_KEY = "tello_live_xxx";
    private static final Function<String, String> NO_ENV = name -> null;

    @Test
    void connectsToProductionGatewayWithoutUrlOrTelloUrl() {
        assertEquals("wss://api.telloai.io/sdk", TelloClient.resolve(API_KEY, null, NO_ENV).url());
    }

    @Test
    void urlArgumentOverridesTelloUrlWhichOverridesDefault() {
        Map<String, String> env = Map.of("TELLO_URL", "wss://staging.example.com/sdk");
        assertEquals("wss://staging.example.com/sdk", TelloClient.resolve(API_KEY, null, env::get).url());
        assertEquals("ws://localhost:3000/sdk",
                TelloClient.resolve(API_KEY, "ws://localhost:3000/sdk", env::get).url());
    }
}
