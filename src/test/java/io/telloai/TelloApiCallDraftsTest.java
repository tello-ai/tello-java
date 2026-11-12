package io.telloai;

import static io.telloai.TelloApiTransportTest.failure;
import static io.telloai.TelloApiTransportTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.telloai.errors.TelloApiConnectionException;
import io.telloai.errors.TelloApiException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code api.callDrafts()}: create, revise and confirm over {@code /v1/call-drafts}. */
class TelloApiCallDraftsTest {

    private static final String UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String DRAFT = "{\"id\":\"cd_1\",\"status\":\"pendingConfirmation\",\"summary\":\"요약\"}";
    private static final String CALL = "{\"id\":\"call_1\",\"status\":\"queued\"}";

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
    void createPostsTheBodyWithoutAKeyByDefault() throws Exception {
        fake.reply(201, DRAFT);
        JsonObject body = json("{\"agentId\":\"a1\",\"to\":\"01012345678\",\"request\":\"내일 예약 확인\"}");
        assertEquals(json(DRAFT), api.callDrafts().create(body).join());

        FakeApi.Request r = fake.take();
        assertEquals("POST", r.method());
        assertEquals("/v1/call-drafts", r.path());
        assertEquals(body, json(r.body()));
        assertNull(r.header("Idempotency-Key"));
    }

    @Test
    void createReturnsEveryDraftStatusAsSuccess() {
        for (String status : new String[] {"needsClarification", "refused", "confirmed"}) {
            fake.reply(201, "{\"id\":\"cd_2\",\"status\":\"" + status + "\"}");
            assertEquals(status, api.callDrafts().create(new JsonObject()).join().get("status").getAsString());
        }
    }

    @Test
    void createSendsTheGivenKey() throws Exception {
        fake.reply(201, DRAFT);
        api.callDrafts().create(new JsonObject(), "draft-1").join();
        assertEquals("draft-1", fake.take().header("Idempotency-Key"));
    }

    @Test
    void draftRequestsWaitTheLongTimeout() {
        TelloApi timed = TelloApi.builder().apiKey("k").apiUrl(fake.url())
                .timeout(Duration.ofMillis(300)).longTimeout(Duration.ofSeconds(5)).build();
        fake.replyAfter(800, 201, DRAFT).replyAfter(800, 200, DRAFT).replyAfter(800, 201, CALL);
        timed.callDrafts().create(new JsonObject()).join();
        timed.callDrafts().revise("cd_1", "6시로").join();
        timed.callDrafts().confirm("cd_1").join();
    }

    @Test
    void revisePostsTheRequestToTheDraft() throws Exception {
        fake.reply(200, "{\"id\":\"cd_2\",\"status\":\"pendingConfirmation\"}");
        JsonObject next = api.callDrafts().revise("cd/1", "4시도 안 되면 6시로").join();
        assertEquals("cd_2", next.get("id").getAsString());

        FakeApi.Request r = fake.take();
        assertEquals("POST", r.method());
        assertEquals("/v1/call-drafts/cd%2F1/revise", r.path());
        assertEquals(json("{\"request\":\"4시도 안 되면 6시로\"}"), json(r.body()));
        assertNull(r.header("Idempotency-Key"));
    }

    @Test
    void reviseSendsTheGivenKey() throws Exception {
        fake.reply(200, DRAFT);
        api.callDrafts().revise("cd_1", "x", "rev-1").join();
        assertEquals("rev-1", fake.take().header("Idempotency-Key"));
    }

    @Test
    void reviseOnTwoHundredOneIsAnInvalidResponse() {
        fake.reply(201, DRAFT);
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.callDrafts().revise("cd_1", "x")));
        assertEquals("invalid_response", e.code);
    }

    @Test
    void confirmPostsNoBodyWithAGeneratedUuidKey() throws Exception {
        fake.reply(201, CALL);
        assertEquals(json(CALL), api.callDrafts().confirm("cd_1").join());

        FakeApi.Request r = fake.take();
        assertEquals("POST", r.method());
        assertEquals("/v1/call-drafts/cd_1/confirm", r.path());
        assertEquals("", r.body());
        assertNull(r.header("Content-Type"));
        assertTrue(r.header("Idempotency-Key").matches(UUID_V4), r.header("Idempotency-Key"));
    }

    @Test
    void confirmSendsTheGivenKey() throws Exception {
        fake.reply(201, CALL);
        api.callDrafts().confirm("cd_1", "confirm-1").join();
        assertEquals("confirm-1", fake.take().header("Idempotency-Key"));
    }

    @Test
    void confirmErrorsCarryTheGeneratedKey() throws Exception {
        fake.reply(404, "{\"statusCode\":404,\"message\":\"Draft not found\",\"error\":\"draft_not_found\"}");
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.callDrafts().confirm("cd_999")));
        assertEquals("draft_not_found", e.code);
        assertEquals(fake.take().header("Idempotency-Key"), e.idempotencyKey);
        assertTrue(e.idempotencyKey.matches(UUID_V4));
    }

    @Test
    void confirmTransportFailuresCarryTheGeneratedKey() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        TelloApi dead = TelloApi.builder().apiKey("k").apiUrl("http://127.0.0.1:" + port + "/v1").build();
        TelloApiConnectionException e = assertInstanceOf(TelloApiConnectionException.class,
                failure(dead.callDrafts().confirm("cd_1")));
        assertTrue(e.idempotencyKey.matches(UUID_V4));
    }

    @Test
    void confirmOnTwoHundredIsAnInvalidResponse() {
        fake.reply(200, CALL);
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.callDrafts().confirm("cd_1", "confirm-2")));
        assertEquals("invalid_response", e.code);
        assertEquals("confirm-2", e.idempotencyKey);
    }

    @Test
    void rejectsBadArgumentsWithoutARequest() {
        assertThrows(IllegalArgumentException.class, () -> api.callDrafts().confirm(""));
        assertThrows(IllegalArgumentException.class, () -> api.callDrafts().confirm("cd_1", "a,b"));
        assertThrows(IllegalArgumentException.class, () -> api.callDrafts().revise("", "x"));
        assertThrows(IllegalArgumentException.class, () -> api.callDrafts().revise("cd_1", null));
        assertThrows(IllegalArgumentException.class, () -> api.callDrafts().create(null));
        assertEquals(0, fake.requestCount());
    }
}
