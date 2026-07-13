// Live integration example: sends one real SMS. Not part of the Gradle build.

import ai.tello.EventType;
import ai.tello.TelloClient;
import ai.tello.events.ErrorEvent;
import ai.tello.events.SmsSentEvent;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SendSms {

    private static final long RESPONSE_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        String requestId = "live-sms-" + UUID.randomUUID();
        CountDownLatch response = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        try (TelloClient client = new TelloClient(config.apiKey, config.url).connectBlocking()) {
            client.on(EventType.SMS_SENT, event -> {
                SmsSentEvent sms = (SmsSentEvent) event;
                if (!requestId.equals(sms.requestId)) {
                    return;
                }
                if (!hasSmsId(sms)) {
                    failure.compareAndSet(null, "sms.sent did not include smsId");
                } else {
                    System.out.println("[sms.sent] status=" + sms.status + " smsId=" + sms.smsId);
                }
                response.countDown();
            });
            client.on(EventType.ERROR, event -> {
                ErrorEvent error = (ErrorEvent) event;
                if (requestId.equals(error.requestId)) {
                    failure.compareAndSet(null, "gateway error " + error.code + ": " + error.message);
                    response.countDown();
                }
            });
            client.on(EventType.DISCONNECTED, event -> {
                failure.compareAndSet(null, "WebSocket disconnected before sms.sent");
                response.countDown();
            });

            // Do not retry: SMS delivery is a real side effect and requestId is not an idempotency key.
            client.sendSms(config.smsTo, config.message, null, requestId).join();

            if (!response.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for sms.sent");
            }
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
            System.out.println("SMS sent to " + config.smsTo + " (requestId=" + requestId + ")");
        }
    }

    private record Config(String apiKey, String url, String smsTo, String message) {
        static Config fromEnvironment() {
            requireLiveSideEffects();
            String apiKey = required("TELLO_API_KEY");
            String url = requiredWebSocketUrl("TELLO_URL");
            String smsTo = required("LIVE_SMS_TO");
            String message = optional("LIVE_SMS_MESSAGE", "[Tello live test] SMS delivery check.");
            return new Config(apiKey, url, smsTo, message);
        }
    }

    private static void requireLiveSideEffects() {
        if (!"true".equals(System.getenv("ALLOW_LIVE_SIDE_EFFECTS"))) {
            throw new IllegalStateException(
                    "Refusing to send a real SMS: set ALLOW_LIVE_SIDE_EFFECTS=true explicitly.");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String requiredWebSocketUrl(String name) {
        String value = required(name);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute ws:// or wss:// URL", e);
        }
        if (uri.getHost() == null || (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute ws:// or wss:// URL");
        }
        return value;
    }

    private static String optional(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean hasSmsId(SmsSentEvent sms) {
        return sms.smsId != null && !sms.smsId.isBlank();
    }
}
