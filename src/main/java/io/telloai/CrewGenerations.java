package io.telloai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Crew generation jobs ({@code /v1/crew-generations}): {@code api.crewGenerations()}. */
public final class CrewGenerations {

    private final TelloApi api;

    CrewGenerations(TelloApi api) {
        this.api = api;
    }

    /**
     * The job {@code id}: {@code {"id", "status": "running"|"succeeded"|"failed", ...}}. A
     * succeeded job has its {@code crew}; a failed one its {@code error} and possibly the
     * crew it left behind.
     */
    public CompletableFuture<JsonObject> get(String id) {
        String target = "/crew-generations/" + TelloApi.segment(id, "generationId");
        return api.request("GET", target, null, null, null, api.timeout(), 200);
    }
}
