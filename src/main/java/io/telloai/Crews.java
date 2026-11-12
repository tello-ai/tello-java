package io.telloai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.telloai.errors.CrewGenerationTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Crews of the account ({@code /v1/crews}): {@code api.crews()}. Writes are deployed to
 * live calls at once.
 */
public final class Crews extends CrudResource {

    /** How often {@link #generateAndWait(JsonObject)} reads the job: 2 seconds. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    /** How long {@link #generateAndWait(JsonObject)} waits for the job: 600 seconds. */
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(600);

    Crews(TelloApi api) {
        super(api, "/crews", "crewId");
    }

    /** {@code {"data": [{"id", "name", "description"}...]}}. */
    public CompletableFuture<JsonObject> list() {
        return api.request("GET", path, null, null, null, api.timeout(), 200);
    }

    /**
     * Starts building, deploying and publishing a crew from {@code input}
     * ({@code {"goal": ...}} and the other fields of the public docs). Completes with the
     * job, {@code {"id", "status": "running"|"succeeded"|"failed", ...}}: {@code 202} for
     * a new job, or {@code 200} with the existing job when the same goal is already being
     * built or was just built. The account builds one crew at a time; another goal while
     * one runs fails with {@code crew_generation_in_progress} (409, the running job's id in
     * {@code data.id}). Follow the job with {@link CrewGenerations#get(String)}, or use
     * {@link #generateAndWait(JsonObject)}.
     */
    public CompletableFuture<JsonObject> generate(JsonObject input) {
        return generate(input, null);
    }

    /** {@link #generate(JsonObject)} with {@code Idempotency-Key: idempotencyKey} (null sends none). */
    public CompletableFuture<JsonObject> generate(JsonObject input, String idempotencyKey) {
        return api.request("POST", path + "/generate", null, required(input, "input"), idempotencyKey,
                api.longTimeout(), 202, 200);
    }

    /**
     * {@link #generateAndWait(JsonObject, Duration, Duration, String)} polling every
     * {@link #DEFAULT_POLL_INTERVAL} for up to {@link #DEFAULT_WAIT_TIMEOUT}, without an
     * idempotency key.
     */
    public CompletableFuture<JsonObject> generateAndWait(JsonObject input) {
        return generateAndWait(input, DEFAULT_POLL_INTERVAL, DEFAULT_WAIT_TIMEOUT, null);
    }

    /**
     * Generates a crew and polls the job every {@code pollInterval} until it is no longer
     * {@code running}, then completes with the job. A {@code failed} job is returned, not
     * thrown: branch on its {@code status}. When {@code timeout} passes first the future
     * fails with {@link CrewGenerationTimeoutException} carrying the job id (the job keeps
     * running). Request errors fail it as in {@link #generate(JsonObject, String)}.
     */
    public CompletableFuture<JsonObject> generateAndWait(JsonObject input, Duration pollInterval,
                                                         Duration timeout, String idempotencyKey) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("pollInterval and timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        return generate(input, idempotencyKey)
                .thenCompose(job -> follow(job, null, pollInterval, timeout, deadline));
    }

    private CompletableFuture<JsonObject> follow(JsonObject job, String expectedId, Duration pollInterval,
                                                 Duration timeout, long deadline) {
        String id = string(job, "id");
        String status = string(job, "status");
        if (id == null || id.isEmpty() || status == null || (expectedId != null && !id.equals(expectedId))) {
            return CompletableFuture.failedFuture(
                    TelloApi.invalidResponse(200, "invalid crew generation job", null));
        }
        if (!status.equals("running")) {
            return CompletableFuture.completedFuture(job);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return CompletableFuture.failedFuture(new CrewGenerationTimeoutException(id,
                    "crew generation " + id + " is still running after " + timeout));
        }
        Executor later = CompletableFuture.delayedExecutor(
                Math.min(pollInterval.toNanos(), remaining), TimeUnit.NANOSECONDS);
        return CompletableFuture.runAsync(() -> { }, later)
                .thenCompose(ignored -> api.crewGenerations().get(id))
                .thenCompose(next -> follow(next, id, pollInterval, timeout, deadline));
    }

    private static String string(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
