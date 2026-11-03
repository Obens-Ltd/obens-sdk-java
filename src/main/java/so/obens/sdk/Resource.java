package so.obens.sdk;

import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Farmers or facilities. A push is a map: build it with a {@link Mapper} or by hand. */
public final class Resource {
    public static final int MAX_BULK = 500;

    private final Http http;
    private final String path;

    Resource(Http http, String path) {
        this.http = http;
        this.path = path;
    }

    /** Creates the record the first time {@code externalId} is seen, updates it afterwards. Safe to repeat. */
    public SyncResult upsert(Map<String, Object> push) {
        var externalId = push.get("externalId");
        if (externalId == null || externalId.toString().isBlank())
            throw new IllegalArgumentException("externalId is required");
        return http.request("PUT", path + "/" + encode(externalId.toString()), push, new TypeReference<>() {
        });
    }

    /** Upserts many in requests of at most 500. One result per item, in order, failures included. */
    public List<SyncResult> bulk(List<Map<String, Object>> items) {
        return bulk(items, MAX_BULK);
    }

    public List<SyncResult> bulk(List<Map<String, Object>> items, int chunkSize) {
        var size = Math.min(chunkSize, MAX_BULK);
        var results = new ArrayList<SyncResult>();
        for (int i = 0; i < items.size(); i += size) {
            List<SyncResult> part = http.request("POST", path + "/bulk",
                    Map.of("items", items.subList(i, Math.min(i + size, items.size()))), new TypeReference<>() {
                    });
            results.addAll(part);
        }
        return results;
    }

    public Map<String, Object> get(String externalId) {
        return http.request("GET", path + "/" + encode(externalId), null, new TypeReference<>() {
        });
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
