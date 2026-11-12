package io.telloai;

import static io.telloai.TelloApiTransportTest.failure;
import static io.telloai.TelloApiTransportTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.telloai.errors.TelloApiException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code api.agents()} and {@code api.crews()}: list and CRUD over {@code /v1/agents} and {@code /v1/crews}. */
class TelloApiAgentsCrewsTest {

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

    static Stream<Arguments> resources() {
        Function<TelloApi, CrudResource> agents = TelloApi::agents;
        Function<TelloApi, CrudResource> crews = TelloApi::crews;
        return Stream.of(Arguments.of("/v1/agents", agents), Arguments.of("/v1/crews", crews));
    }

    @Test
    void agentsListReturnsTheTargetList() throws Exception {
        String body = "{\"data\":[{\"id\":\"a1\",\"name\":\"상담\",\"description\":\"\",\"isDefault\":true}]}";
        fake.reply(200, body);
        assertEquals(json(body), api.agents().list().join());
        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals("/v1/agents", r.path());
        assertNull(r.query());
    }

    @Test
    void crewsListReturnsTheTargetList() throws Exception {
        String body = "{\"data\":[{\"id\":\"c1\",\"name\":\"예약\",\"description\":\"\"}]}";
        fake.reply(200, body);
        assertEquals(json(body), api.crews().list().join());
        assertEquals("/v1/crews", fake.take().path());
    }

    @ParameterizedTest
    @MethodSource("resources")
    void getReadsOneItem(String base, Function<TelloApi, CrudResource> of) throws Exception {
        fake.reply(200, "{\"id\":\"x 1\"}");
        assertEquals(json("{\"id\":\"x 1\"}"), of.apply(api).get("x 1").join());
        FakeApi.Request r = fake.take();
        assertEquals("GET", r.method());
        assertEquals(base + "/x%201", r.path());
    }

    @ParameterizedTest
    @MethodSource("resources")
    void createPostsWithAndWithoutKey(String base, Function<TelloApi, CrudResource> of) throws Exception {
        fake.reply(201, "{\"id\":\"n1\"}").reply(201, "{\"id\":\"n2\"}");
        JsonObject body = json("{\"name\":\"새 항목\"}");
        assertEquals(json("{\"id\":\"n1\"}"), of.apply(api).create(body).join());
        of.apply(api).create(body, "create-1").join();

        FakeApi.Request first = fake.take();
        assertEquals("POST", first.method());
        assertEquals(base, first.path());
        assertEquals(body, json(first.body()));
        assertNull(first.header("Idempotency-Key"));
        assertEquals("create-1", fake.take().header("Idempotency-Key"));
    }

    @ParameterizedTest
    @MethodSource("resources")
    void updatePatches(String base, Function<TelloApi, CrudResource> of) throws Exception {
        fake.reply(200, "{\"id\":\"i1\",\"name\":\"바뀜\"}");
        of.apply(api).update("i1", json("{\"name\":\"바뀜\"}")).join();
        FakeApi.Request r = fake.take();
        assertEquals("PATCH", r.method());
        assertEquals(base + "/i1", r.path());
        assertEquals(json("{\"name\":\"바뀜\"}"), json(r.body()));
    }

    @ParameterizedTest
    @MethodSource("resources")
    void deleteCompletesWithNull(String base, Function<TelloApi, CrudResource> of) throws Exception {
        fake.reply(204, "");
        assertNull(of.apply(api).delete("i1").join());
        FakeApi.Request r = fake.take();
        assertEquals("DELETE", r.method());
        assertEquals(base + "/i1", r.path());
    }

    @ParameterizedTest
    @MethodSource("resources")
    void createOnTwoHundredIsAnInvalidResponse(String base, Function<TelloApi, CrudResource> of) {
        fake.reply(200, "{\"id\":\"n1\"}");
        CompletableFuture<JsonObject> created = of.apply(api).create(new JsonObject());
        assertEquals("invalid_response", assertInstanceOf(TelloApiException.class, failure(created)).code);
    }

    @ParameterizedTest
    @MethodSource("resources")
    void emptyIdIsAnArgumentError(String base, Function<TelloApi, CrudResource> of) {
        assertThrows(IllegalArgumentException.class, () -> of.apply(api).get(""));
        assertEquals(0, fake.requestCount());
    }

    @Test
    void agentConflictCarriesItsData() {
        fake.reply(409, "{\"statusCode\":409,\"message\":\"Agent is in use\",\"error\":\"agent_in_use\","
                + "\"data\":{\"crewIds\":[\"c1\"]}}");
        TelloApiException e = assertInstanceOf(TelloApiException.class, failure(api.agents().delete("a1")));
        assertEquals(409, e.status);
        assertEquals("agent_in_use", e.code);
        assertEquals(json("{\"crewIds\":[\"c1\"]}"), e.data);
    }
}
