package io.telloai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Crews of the account ({@code /v1/crews}): {@code api.crews()}. Writes are deployed to
 * live calls at once.
 */
public final class Crews extends CrudResource {

    Crews(TelloApi api) {
        super(api, "/crews", "crewId");
    }

    /** {@code {"data": [{"id", "name", "description"}...]}}. */
    public CompletableFuture<JsonObject> list() {
        return api.request("GET", path, null, null, null, api.timeout(), 200);
    }
}
