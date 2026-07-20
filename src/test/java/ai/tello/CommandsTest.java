package ai.tello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandsTest {

    private JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void authUsesEnvelopeAndCarriesTokenKey() {
        JsonObject frame = parse(Commands.auth("tello_live_xxx", "auth-1"));
        assertEquals("auth", frame.get("event").getAsString());
        JsonObject data = frame.getAsJsonObject("data");
        assertEquals("tello_live_xxx", data.get("token").getAsString());
        assertFalse(data.has("apiKey"));
        assertEquals("auth-1", data.get("requestId").getAsString());
    }

    @Test
    void authOmitsRequestIdWhenAbsent() {
        JsonObject data = parse(Commands.auth("tello_live_xxx", null)).getAsJsonObject("data");
        assertEquals("tello_live_xxx", data.get("token").getAsString());
        assertFalse(data.has("requestId"));
    }

    @Test
    void createCallUsesEnvelopeAndCamelCase() {
        JsonObject frame = parse(Commands.createCall("+821012345678", "hi", Map.of("src", "test"), "r1"));
        assertEquals("createCall", frame.get("event").getAsString());
        JsonObject data = frame.getAsJsonObject("data");
        assertEquals("+821012345678", data.get("to").getAsString());
        assertEquals("hi", data.get("prompt").getAsString());
        assertEquals("test", data.getAsJsonObject("metadata").get("src").getAsString());
        assertEquals("r1", data.get("requestId").getAsString());
    }

    @Test
    void createCallOmitsOptionalFields() {
        JsonObject data = parse(Commands.createCall("+821012345678", "", null, null)).getAsJsonObject("data");
        assertEquals("+821012345678", data.get("to").getAsString());
        assertFalse(data.has("metadata"));
        assertFalse(data.has("requestId"));
    }

    @Test
    void createCallNeverCarriesAgentId() {
        // Contract: the gateway ignores agentId on the SDK path; the frame must not include the key at all.
        JsonObject withOptions = parse(Commands.createCall("+821012345678", "hi", Map.of("src", "test"), "r1"))
                .getAsJsonObject("data");
        assertFalse(withOptions.has("agentId"));
        JsonObject minimal = parse(Commands.createCall("+821012345678", "", null, null)).getAsJsonObject("data");
        assertFalse(minimal.has("agentId"));
    }

    @Test
    void answerAndCancelFrames() {
        JsonObject answer = parse(Commands.answer("yo", "m1", null));
        assertEquals("answer", answer.get("event").getAsString());
        assertEquals("m1", answer.getAsJsonObject("data").get("messageId").getAsString());
        assertEquals("cancel", parse(Commands.cancel()).get("event").getAsString());
    }

    @Test
    void sendDtmfUsesEnvelopeAndCamelCase() {
        JsonObject frame = parse(Commands.sendDtmf("1234#", "m1", "r1"));
        assertEquals("sendDtmf", frame.get("event").getAsString());
        JsonObject data = frame.getAsJsonObject("data");
        assertEquals("1234#", data.get("digits").getAsString());
        assertEquals("m1", data.get("messageId").getAsString());
        assertEquals("r1", data.get("requestId").getAsString());
    }

    @Test
    void sendDtmfOmitsOptionalFields() {
        JsonObject data = parse(Commands.sendDtmf("5678", null, null)).getAsJsonObject("data");
        assertEquals("5678", data.get("digits").getAsString());
        assertFalse(data.has("messageId"));
        assertFalse(data.has("requestId"));
    }

    @Test
    void summaryFrame() {
        JsonObject summary = parse(Commands.getSummary("call-1", "summary-1"));
        assertEquals("getSummary", summary.get("event").getAsString());
        assertEquals("call-1", summary.getAsJsonObject("data").get("callId").getAsString());
        assertEquals("summary-1", summary.getAsJsonObject("data").get("requestId").getAsString());
    }
}
