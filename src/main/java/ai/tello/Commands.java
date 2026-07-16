package ai.tello;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Outbound command frame builders.
 *
 * <p>Client &rarr; server frames use the {@code @nestjs/platform-ws} routing envelope
 * {@code {"event":"<command>","data":{...}}}. This asymmetry (enveloped outbound,
 * flat inbound) is intentional; keep it isolated here.
 */
public final class Commands {

    private static final Gson GSON = new Gson();

    private Commands() {}

    /**
     * The authentication handshake frame. It MUST be the first frame a client sends
     * after the socket opens; no other command may be sent until the server replies
     * with {@code auth.ok}. The raw API key travels only in this frame body's
     * {@code token} field — never in a URL query or upgrade header.
     */
    public static String auth(String apiKey, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("token", apiKey);
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("auth", data);
    }

    public static String createCall(String to, String prompt, Map<String, ?> metadata, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("to", to);
        data.addProperty("prompt", prompt == null ? "" : prompt);
        if (metadata != null) {
            data.add("metadata", GSON.toJsonTree(metadata));
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("createCall", data);
    }

    public static String answer(String text, String messageId, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("text", text == null ? "" : text);
        if (messageId != null) {
            data.addProperty("messageId", messageId);
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("answer", data);
    }

    public static String sendDtmf(String digits, String messageId, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("digits", digits == null ? "" : digits);
        if (messageId != null) {
            data.addProperty("messageId", messageId);
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("sendDtmf", data);
    }

    public static String cancel() {
        return envelope("cancel", new JsonObject());
    }

    public static String getSummary(String callId, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("callId", callId);
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("getSummary", data);
    }

    public static String sendSms(String to, String message, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("to", to);
        data.addProperty("message", message);
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("sendSms", data);
    }

    private static String envelope(String event, JsonElement data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("event", event);
        frame.add("data", data);
        return GSON.toJson(frame);
    }
}
