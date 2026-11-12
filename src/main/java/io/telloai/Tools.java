package io.telloai;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Tools agents can call during a call ({@code /v1/tools}): {@code api.tools()}.
 * Bodies and results are the JSON objects of the public docs; a created or updated
 * tool carries its {@code revision}.
 */
public final class Tools extends CrudResource {

    Tools(TelloApi api) {
        super(api, "/tools", "toolId");
    }

    /** The first page: {@code {"data": [tool...], "nextCursor": string|null}}. */
    public CompletableFuture<JsonObject> list() {
        return list(null, null);
    }

    /**
     * One page of at most {@code limit} tools after {@code cursor} (the previous page's
     * {@code nextCursor}); null leaves either out.
     */
    public CompletableFuture<JsonObject> list(Integer limit, String cursor) {
        StringBuilder query = new StringBuilder();
        if (limit != null) {
            query.append("limit=").append(limit);
        }
        if (cursor != null && !cursor.isEmpty()) {
            query.append(query.length() == 0 ? "" : "&").append("cursor=").append(TelloApi.queryValue(cursor));
        }
        return api.request("GET", path, query.length() == 0 ? null : query.toString(), null, null,
                api.timeout(), 200);
    }
}
