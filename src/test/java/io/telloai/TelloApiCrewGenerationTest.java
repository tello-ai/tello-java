package io.telloai;

import static io.telloai.TelloApiTransportTest.failure;
import static io.telloai.TelloApiTransportTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.telloai.errors.CrewGenerationTimeoutException;
import io.telloai.errors.TelloApiConnectionException;
import io.telloai.errors.TelloApiException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code crews().generate}, {@code crewGenerations().get} and {@code crews().generateAndWait}. */
class TelloApiCrewGenerationTest {

    private static final String GOAL = "{\"goal\":\"내일 예약을 확인한다\"}";
    private static final String RUNNING = "{\"id\":\"57\",\"status\":\"running\",\"goal\":\"내일 예약을 확인한다\"}";
    private static final String SUCCEEDED = "{\"id\":\"57\",\"status\":\"succeeded\",\"crew\":{\"id\":\"c9\"}}";
    private static final String FAILED =
            "{\"id\":\"57\",\"status\":\"failed\",\"error\":{\"code\":\"deploy_failed\",\"message\":\"x\"}}";
    private static final Duration FAST = Duration.ofMillis(10);

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
    void generatePostsTheGoalAndReturnsTheAcceptedJob() throws Exception {
        fake.reply(202, RUNNING);
        assertEquals(json(RUNNING), api.crews().generate(json(GOAL)).join());

        FakeApi.Request r = fake.take();
        assertEquals("POST", r.method());
        assertEquals("/v1/crews/generate", r.path());
        assertEquals(json(GOAL), json(r.body()));
        assertNull(r.header("Idempotency-Key"));
    }

    @Test
    void generateAcceptsTheExistingJobOnTwoHundred() {
        fake.reply(200, SUCCEEDED);
        assertEquals(json(SUCCEEDED), api.crews().generate(json(GOAL)).join());
    }

    @Test
    void generateSendsTheGivenKey() throws Exception {
        fake.reply(202, RUNNING);
        api.crews().generate(json(GOAL), "gen-1").join();
        assertEquals("gen-1", fake.take().header("Idempotency-Key"));
    }

    @Test
    void generateOnTwoHundredOneIsAnInvalidResponse() {
        fake.reply(201, RUNNING);
        TelloApiException e = assertInstanceOf(TelloApiException.class, failure(api.crews().generate(json(GOAL))));
        assertEquals("invalid_response", e.code);
    }

    @Test
    void generateWaitsTheLongTimeoutWhileReadsUseTheShortOne() {
        TelloApi timed = TelloApi.builder().apiKey("k").apiUrl(fake.url())
                .timeout(Duration.ofMillis(300)).longTimeout(Duration.ofSeconds(5)).build();
        fake.replyAfter(800, 202, RUNNING).replyAfter(800, 200, RUNNING);
        assertEquals(json(RUNNING), timed.crews().generate(json(GOAL)).join());
        assertInstanceOf(TelloApiConnectionException.class, failure(timed.crewGenerations().get("57")));
    }

    @Test
    void getReadsTheJobByEncodedId() throws Exception {
        fake.reply(200, SUCCEEDED);
        assertEquals(json(SUCCEEDED), api.crewGenerations().get("5/7").join());
        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals("/v1/crew-generations/5%2F7", r.path());
    }

    @Test
    void getRejectsAnEmptyId() {
        assertThrows(IllegalArgumentException.class, () -> api.crewGenerations().get(""));
        assertEquals(0, fake.requestCount());
    }

    @Test
    void getReportsAnUnknownJob() {
        fake.reply(404, "{\"statusCode\":404,\"message\":\"not found\",\"error\":\"crew_generation_not_found\"}");
        TelloApiException e = assertInstanceOf(TelloApiException.class, failure(api.crewGenerations().get("1")));
        assertEquals("crew_generation_not_found", e.code);
    }

    @Test
    void generateAndWaitPollsUntilTheJobIsNoLongerRunning() throws Exception {
        fake.reply(202, RUNNING).reply(200, RUNNING).reply(200, SUCCEEDED);
        JsonObject job = api.crews().generateAndWait(json(GOAL), FAST, Duration.ofSeconds(5), "gen-2").join();

        assertEquals(json(SUCCEEDED), job);
        FakeApi.Request generate = fake.take();
        assertEquals("/v1/crews/generate", generate.path());
        assertEquals("gen-2", generate.header("Idempotency-Key"));
        assertEquals("/v1/crew-generations/57", fake.take().path());
        assertEquals("/v1/crew-generations/57", fake.take().path());
        assertEquals(0, fake.requestCount());
    }

    @Test
    void generateAndWaitReturnsAFinishedJobWithoutPolling() {
        fake.reply(200, SUCCEEDED);
        assertEquals(json(SUCCEEDED), api.crews().generateAndWait(json(GOAL)).join());
        assertEquals(1, fake.requestCount());
    }

    @Test
    void generateAndWaitReturnsAFailedJobRatherThanFailing() {
        fake.reply(202, RUNNING).reply(200, FAILED);
        JsonObject job = api.crews().generateAndWait(json(GOAL), FAST, Duration.ofSeconds(5), null).join();
        assertEquals("failed", job.get("status").getAsString());
    }

    @Test
    void generateAndWaitTimesOutWithTheJobId() {
        fake.reply(202, RUNNING).replyOtherwise(200, RUNNING);
        Throwable e = failure(api.crews().generateAndWait(json(GOAL), FAST, Duration.ofMillis(150), null));
        CrewGenerationTimeoutException timeout = assertInstanceOf(CrewGenerationTimeoutException.class, e);
        assertEquals("57", timeout.generationId);
        assertTrue(timeout.getMessage().contains("57"));
    }

    @Test
    void generateAndWaitPassesTheInProgressConflictThrough() {
        fake.reply(409, "{\"statusCode\":409,\"message\":\"busy\",\"error\":\"crew_generation_in_progress\","
                + "\"data\":{\"id\":\"56\"}}");
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.crews().generateAndWait(json(GOAL))));
        assertEquals(409, e.status);
        assertEquals("crew_generation_in_progress", e.code);
        assertEquals("56", e.data.get("id").getAsString());
    }

    @Test
    void generateAndWaitRejectsAJobWithoutId() {
        fake.reply(202, "{\"status\":\"running\"}");
        TelloApiException e = assertInstanceOf(TelloApiException.class,
                failure(api.crews().generateAndWait(json(GOAL))));
        assertEquals("invalid_response", e.code);
    }
}
