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

    public static String createCall(String to, String agentId, String prompt, Map<String, ?> metadata, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("to", to);
        data.addProperty("agentId", agentId);
        data.addProperty("prompt", prompt == null ? "" : prompt);
        if (metadata != null) {
            data.add("metadata", GSON.toJsonTree(metadata));
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("create_call", data);
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

    public static String cancel() {
        return envelope("cancel", new JsonObject());
    }

    public static String listAgents(String requestId) {
        JsonObject data = new JsonObject();
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("listAgents", data);
    }

    public static String getSummary(String callId, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("callId", callId);
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return envelope("getSummary", data);
    }

    public static String sendSms(String to, String message, String callId, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("to", to);
        data.addProperty("message", message);
        if (callId != null) {
            data.addProperty("callId", callId);
        }
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
