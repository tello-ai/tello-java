package io.telloai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** get, create, update and delete of one REST collection ({@code /tools}, {@code /agents}, {@code /crews}). */
abstract class CrudResource {

    final TelloApi api;
    final String path;
    private final String idName;

    CrudResource(TelloApi api, String path, String idName) {
        this.api = api;
        this.path = path;
        this.idName = idName;
    }

    /** One item by id ({@code 200}). */
    public CompletableFuture<JsonObject> get(String id) {
        String target = path + "/" + TelloApi.segment(id, idName);
        return api.request("GET", target, null, null, null, api.timeout(), 200);
    }

    /**
     * Creates an item ({@code 201}); it goes live in the account at once. No
     * {@code Idempotency-Key} is sent, so a retry after a lost response may create a
     * second item; use {@link #create(JsonObject, String)} to retry safely.
     */
    public CompletableFuture<JsonObject> create(JsonObject body) {
        return create(body, null);
    }

    /**
     * Creates an item ({@code 201}) with {@code Idempotency-Key: idempotencyKey} (null sends
     * none). Resending the same body with the same key returns the first result.
     */
    public CompletableFuture<JsonObject> create(JsonObject body, String idempotencyKey) {
        return api.request("POST", path, null, required(body, "body"), idempotencyKey, api.longTimeout(), 201);
    }

    /** Changes the fields in {@code patch} ({@code 200}); it goes live at once. */
    public CompletableFuture<JsonObject> update(String id, JsonObject patch) {
        String target = path + "/" + TelloApi.segment(id, idName);
        return api.request("PATCH", target, null, required(patch, "patch"), null, api.longTimeout(), 200);
    }

    /** Deletes an item ({@code 204}); the future completes with null. */
    public CompletableFuture<Void> delete(String id) {
        String target = path + "/" + TelloApi.segment(id, idName);
        return api.request("DELETE", target, null, null, null, api.longTimeout(), 204).thenApply(ignored -> null);
    }

    static JsonObject required(JsonObject body, String name) {
        if (body == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return body;
    }
}
