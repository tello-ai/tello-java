package io.telloai;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Call drafts ({@code /v1/call-drafts}): {@code api.callDrafts()}. Describe a call in
 * words, read back the draft the service prepared, revise it, and confirm it.
 *
 * <p>Every draft status ({@code pendingConfirmation}, {@code needsClarification},
 * {@code refused}, {@code confirmed}) is a successful result; branch on
 * {@code status}. <b>{@link #confirm(String)} places a real, billed phone call.</b>
 */
public final class CallDrafts {

    private final TelloApi api;

    CallDrafts(TelloApi api) {
        this.api = api;
    }

    /** Drafts a call from {@code body} ({@code 201}), without an idempotency key. */
    public CompletableFuture<JsonObject> create(JsonObject body) {
        return create(body, null);
    }

    /** Drafts a call from {@code body} ({@code 201}) with {@code Idempotency-Key: idempotencyKey} (null sends none). */
    public CompletableFuture<JsonObject> create(JsonObject body, String idempotencyKey) {
        return api.request("POST", "/call-drafts", null, CrudResource.required(body, "body"), idempotencyKey,
                api.longTimeout(), 201);
    }

    /**
     * Revises draft {@code draftId} with {@code request}, what to change in words
     * ({@code 200}). The result is a new draft with a new id; the old one can no longer be
     * used.
     */
    public CompletableFuture<JsonObject> revise(String draftId, String request) {
        return revise(draftId, request, null);
    }

    /** {@link #revise(String, String)} with {@code Idempotency-Key: idempotencyKey} (null sends none). */
    public CompletableFuture<JsonObject> revise(String draftId, String request, String idempotencyKey) {
        String target = "/call-drafts/" + TelloApi.segment(draftId, "draftId") + "/revise";
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        JsonObject body = new JsonObject();
        body.addProperty("request", request);
        return api.request("POST", target, null, body, idempotencyKey, api.longTimeout(), 200);
    }

    /**
     * Confirms draft {@code draftId} and <b>places the real, billed call</b>
     * ({@code 201}); completes with the call. The API requires an idempotency key, so a
     * random UUID is sent; a failure carries it in {@code idempotencyKey}. Retry only
     * with that key ({@link #confirm(String, String)}), never without it, or a lost
     * response may become a second call.
     */
    public CompletableFuture<JsonObject> confirm(String draftId) {
        return confirm(draftId, null);
    }

    /** {@link #confirm(String)} with {@code Idempotency-Key: idempotencyKey}; null generates a UUID. */
    public CompletableFuture<JsonObject> confirm(String draftId, String idempotencyKey) {
        String target = "/call-drafts/" + TelloApi.segment(draftId, "draftId") + "/confirm";
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        return api.request("POST", target, null, null, key, api.longTimeout(), 201);
    }
}
