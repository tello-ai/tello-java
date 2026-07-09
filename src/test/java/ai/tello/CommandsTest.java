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
    void createCallUsesEnvelopeAndCamelCase() {
        JsonObject frame = parse(Commands.createCall("+821012345678", "agent-1", "hi", Map.of("src", "test"), "r1"));
        assertEquals("create_call", frame.get("event").getAsString());
        JsonObject data = frame.getAsJsonObject("data");
        assertEquals("+821012345678", data.get("to").getAsString());
        assertEquals("agent-1", data.get("agentId").getAsString());
        assertEquals("hi", data.get("prompt").getAsString());
        assertEquals("test", data.getAsJsonObject("metadata").get("src").getAsString());
        assertEquals("r1", data.get("requestId").getAsString());
    }

    @Test
    void createCallOmitsOptionalFields() {
        JsonObject data = parse(Commands.createCall("+821012345678", "agent-1", "", null, null)).getAsJsonObject("data");
        assertEquals("+821012345678", data.get("to").getAsString());
        assertFalse(data.has("metadata"));
        assertFalse(data.has("requestId"));
    }

    @Test
    void answerAndCancelFrames() {
        JsonObject answer = parse(Commands.answer("yo", "m1", null));
        assertEquals("answer", answer.get("event").getAsString());
        assertEquals("m1", answer.getAsJsonObject("data").get("messageId").getAsString());
        assertEquals("cancel", parse(Commands.cancel()).get("event").getAsString());
    }

    @Test
    void listAgentsFrameUsesRequestIdWhenProvided() {
        JsonObject frame = parse(Commands.listAgents("agents-1"));
        assertEquals("listAgents", frame.get("event").getAsString());
        assertEquals("agents-1", frame.getAsJsonObject("data").get("requestId").getAsString());
    }

    @Test
    void listAgentsFrameOmitsEmptyRequestId() {
        JsonObject data = parse(Commands.listAgents(null)).getAsJsonObject("data");
        assertFalse(data.has("requestId"));
    }
}
