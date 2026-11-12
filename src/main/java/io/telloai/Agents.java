package io.telloai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Agents of the account ({@code /v1/agents}): {@code api.agents()}. Writes are deployed
 * to live calls at once.
 */
public final class Agents extends CrudResource {

    Agents(TelloApi api) {
        super(api, "/agents", "agentId");
    }

    /** {@code {"data": [{"id", "name", "description", "isDefault"}...]}}. */
    public CompletableFuture<JsonObject> list() {
        return api.request("GET", path, null, null, null, api.timeout(), 200);
    }
}
