package io.telloai;

import static io.telloai.TelloApiTransportTest.failure;
import static io.telloai.TelloApiTransportTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.telloai.errors.TelloApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code api.tools()}: list, get, create, update and delete over {@code /v1/tools}. */
class TelloApiToolsTest {

    private static final String TOOL = "{\"id\":\"tool_1\",\"type\":\"endCall\",\"name\":\"끊기\",\"revision\":3}";

    private FakeApi fake;
    private TelloApi api;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeApi();
        api = TelloApi.builder().apiKey("k").apiUrl(fake.url()).build();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void listGetsTheFirstPageWithoutQuery() throws Exception {
        fake.reply(200, "{\"data\":[" + TOOL + "],\"nextCursor\":null}");
        JsonObject page = api.tools().list().join();

        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals("/v1/tools", r.path());
        assertNull(r.query());
        assertEquals(json("{\"data\":[" + TOOL + "],\"nextCursor\":null}"), page);
    }

    @Test
    void listSendsLimitAndEncodedCursor() throws Exception {
        fake.reply(200, "{\"data\":[],\"nextCursor\":\"c2\"}");
        api.tools().list(5, "a b/c=").join();
        assertEquals("limit=5&cursor=a+b%2Fc%3D", fake.take().query());
    }

    @Test
    void getEncodesTheIdAsOnePathSegment() throws Exception {
        fake.reply(200, TOOL);
        assertEquals(json(TOOL), api.tools().get("tool/1 x").join());
        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals("/v1/tools/tool%2F1%20x", r.path());
    }

    @Test
    void rejectsAnEmptyIdWithoutARequest() {
        assertThrows(IllegalArgumentException.class, () -> api.tools().get(""));
        assertThrows(IllegalArgumentException.class, () -> api.tools().get(null));
        assertThrows(IllegalArgumentException.class, () -> api.tools().update("", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> api.tools().delete(""));
        assertEquals(0, fake.requestCount());
    }

    @Test
    void createPostsTheBodyWithoutAKeyByDefault() throws Exception {
        fake.reply(201, TOOL);
        JsonObject body = json("{\"type\":\"endCall\",\"name\":\"끊기\",\"functionName\":\"end_call\"}");
        assertEquals(json(TOOL), api.tools().create(body).join());

        FakeApi.Request r = fake.take();
        assertEquals("POST", r.method());
        assertEquals("/v1/tools", r.path());
        assertEquals(body, json(r.body()));
        assertNull(r.header("Idempotency-Key"));
    }

    @Test
    void createSendsTheGivenIdempotencyKey() throws Exception {
        fake.reply(201, TOOL);
        api.tools().create(new JsonObject(), "tool-key").join();
        assertEquals("tool-key", fake.take().header("Idempotency-Key"));
    }

    @Test
    void createRejectsAnInvalidKeyOrMissingBodyWithoutARequest() {
        assertThrows(IllegalArgumentException.class, () -> api.tools().create(new JsonObject(), "a,b"));
        assertThrows(IllegalArgumentException.class, () -> api.tools().create(null));
        assertEquals(0, fake.requestCount());
    }

    @Test
    void createFailsWithInvalidResponseOnTwoHundred() {
        fake.reply(200, TOOL);
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.tools().create(new JsonObject())));
        assertEquals("invalid_response", e.code);
    }

    @Test
    void updatePatchesTheTool() throws Exception {
        fake.reply(200, TOOL);
        JsonObject patch = json("{\"description\":\"통화를 끝낸다\"}");
        assertEquals(json(TOOL), api.tools().update("tool_1", patch).join());

        FakeApi.Request r = fake.take();
        assertEquals("PATCH", r.method());
        assertEquals("/v1/tools/tool_1", r.path());
        assertEquals("application/json", r.header("Content-Type"));
        assertEquals(patch, json(r.body()));
    }

    @Test
    void deleteCompletesWithNullOnNoContent() throws Exception {
        fake.reply(204, "");
        assertNull(api.tools().delete("tool_1").join());
        FakeApi.Request r = fake.take();
        assertEquals("DELETE", r.method());
        assertEquals("/v1/tools/tool_1", r.path());
    }

    @Test
    void deleteFailsWithInvalidResponseOnTwoHundred() {
        fake.reply(200, TOOL);
        TelloApiException e = assertInstanceOf(TelloApiException.class, failure(api.tools().delete("tool_1")));
        assertEquals("invalid_response", e.code);
    }

    @Test
    void getReportsToolNotFound() {
        fake.reply(404, "{\"statusCode\":404,\"message\":\"Tool not found\",\"error\":\"tool_not_found\",\"data\":null}");
        TelloApiException e = assertInstanceOf(TelloApiException.class, failure(api.tools().get("tool_9")));
        assertEquals(404, e.status);
        assertEquals("tool_not_found", e.code);
    }
}
