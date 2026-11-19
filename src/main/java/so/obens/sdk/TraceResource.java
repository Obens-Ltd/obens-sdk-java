package so.obens.sdk;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static so.obens.sdk.Resource.encode;

/**
 * Batches or transactions, which live in the trace api (a second base url, the workspace id as a header). A push is a
 * map: build it with a {@link Mapper} or by hand.
 */
public class TraceResource {
    final Http http;
    final String path;
    private final UnaryOperator<Map<String, Object>> resolver;

    TraceResource(Http http, String path, UnaryOperator<Map<String, Object>> resolver) {
        this.http = http;
        this.path = path;
        this.resolver = resolver;
    }

    /**
     * Creates the record the first time {@code externalId} is seen, updates it afterwards. Safe to repeat. Creation
     * waits for the platform to finish, so the call can take several seconds.
     */
    public SyncResult upsert(Map<String, Object> push) {
        var externalId = push.get("externalId");
        if (externalId == null || externalId.toString().isBlank())
            throw new IllegalArgumentException("externalId is required");
        var body = new LinkedHashMap<>(resolver.apply(push));
        body.remove("externalId");
        return http.request("PUT", path + "/" + encode(externalId.toString()), body, new TypeReference<>() {
        });
    }

    /** {@code ?a=1&b=2} from the pairs (alternating name, value), skipping null and blank values. */
    static String query(Object... pairs) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < pairs.length; i += 2)
            if (pairs[i + 1] != null && !pairs[i + 1].toString().isBlank())
                parts.add(pairs[i] + "=" + encode(pairs[i + 1].toString()));
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    /** Upserts many, one after the other (each creation is a heavy write). One result per item; a failure does not stop the rest. */
    public List<SyncResult> bulk(List<Map<String, Object>> items) {
        var results = new ArrayList<SyncResult>();
        for (var item : items) {
            try {
                results.add(upsert(item));
            } catch (RuntimeException e) {
                var id = item.get("externalId");
                results.add(new SyncResult(id == null ? null : id.toString(), null, SyncResult.Outcome.FAILED, e.getMessage()));
            }
        }
        return results;
    }

    /** Batches of the account (or of one of its sub-actors with {@code ownerId}) in an element. */
    public static final class Batches extends TraceResource {
        Batches(Http http, UnaryOperator<Map<String, Object>> resolver) {
            super(http, "/integration/batches", resolver);
        }

        public Map<String, Object> get(String externalId) {
            return get(externalId, null);
        }

        /** {@code ownerId}: a sub-actor's platform id, for what a purchase from a farmer created. */
        public Map<String, Object> get(String externalId, String ownerId) {
            var query = ownerId == null || ownerId.isBlank() ? "" : "?owner-id=" + encode(ownerId);
            return http.request("GET", path + "/" + encode(externalId) + query, null, new TypeReference<>() {
            });
        }

        /** A page of batches in an element: data, page, size, total, totalPages, hasNext. Null filters are left out. */
        public Map<String, Object> list(String elementId, String ownerId, String search, Integer year, Integer page, Integer size) {
            return http.request("GET", path + query("element-id", elementId, "owner-id", ownerId, "search", search,
                    "year", year, "page", page, "size", size), null, new TypeReference<>() {
            });
        }

        public Map<String, Object> list(String elementId) {
            return list(elementId, null, null, null, null, null);
        }

        /** What the batch's form holds: id, staticId, name, type, value (in the shape {@code data} accepts). */
        public List<Map<String, Object>> data(String externalId, String ownerId) {
            return http.request("GET", path + "/" + encode(externalId) + "/data" + query("owner-id", ownerId), null,
                    new TypeReference<>() {
                    });
        }

        /** The fields of a production method's form: the names {@code data} accepts. */
        public List<Map<String, Object>> schema(String productionMethodId) {
            return http.request("GET", path + "/schema?production-method-id=" + encode(productionMethodId), null,
                    new TypeReference<>() {
                    });
        }
    }

    public static final class Transactions extends TraceResource {
        Transactions(Http http, UnaryOperator<Map<String, Object>> resolver) {
            super(http, "/integration/transactions", resolver);
        }

        public Map<String, Object> get(String externalId) {
            return get(externalId, null);
        }

        /** {@code supplierId}: a sub-actor's platform id, for a purchase from a farmer (default: the key's account). */
        public Map<String, Object> get(String externalId, String supplierId) {
            var query = supplierId == null || supplierId.isBlank() ? "" : "?supplier-id=" + encode(supplierId);
            return http.request("GET", path + "/" + encode(externalId) + query, null, new TypeReference<>() {
            });
        }

        /**
         * A page of the account's transactions in an element, as buyer or seller. {@code type}: BUY | SELL;
         * {@code start} / {@code end}: 2026-01-31. Null filters are left out.
         */
        public Map<String, Object> list(String elementId, String otherAccountId, String type, String start, String end,
                                        String search, Integer page, Integer size) {
            return http.request("GET", path + query("element-id", elementId, "other-account-id", otherAccountId,
                    "type", type, "start", start, "end", end, "search", search, "page", page, "size", size), null,
                    new TypeReference<>() {
                    });
        }

        public Map<String, Object> list(String elementId) {
            return list(elementId, null, null, null, null, null, null, null);
        }

        /** What the transaction's form holds: id, staticId, name, type, value. */
        public List<Map<String, Object>> data(String externalId, String supplierId) {
            return http.request("GET", path + "/" + encode(externalId) + "/data" + query("supplier-id", supplierId), null,
                    new TypeReference<>() {
                    });
        }

        /** The fields of the transaction form an account (the buyer, or the key's when null) has for an element. */
        public List<Map<String, Object>> schema(String elementId, String clientId) {
            var query = "?element-id=" + encode(elementId) + (clientId == null || clientId.isBlank() ? "" : "&client-id=" + encode(clientId));
            return http.request("GET", path + "/schema" + query, null, new TypeReference<>() {
            });
        }
    }
}
