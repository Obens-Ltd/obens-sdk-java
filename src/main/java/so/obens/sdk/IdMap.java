package so.obens.sdk;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Your id <-> platform id for one kind, cached after {@link #load()}. */
public final class IdMap {
    private final ObensClient client;
    private final MappingType type;
    private final Map<String, String> toId = new ConcurrentHashMap<>();
    private final Map<String, String> toExternal = new ConcurrentHashMap<>();

    IdMap(ObensClient client, MappingType type) {
        this.client = client;
        this.type = type;
    }

    /** Downloads the whole table once. */
    public IdMap load() {
        toId.clear();
        toExternal.clear();
        client.mappings().list(type).forEach(m -> put(m.externalId(), m.id()));
        return this;
    }

    /** The platform id for your id, from the cache or the server. Empty when it is unknown. */
    public Optional<String> internal(String externalId) {
        if (!toId.containsKey(externalId))
            lookup(() -> client.mappings().toInternal(type, externalId)).ifPresent(m -> put(m.externalId(), m.id()));
        return Optional.ofNullable(toId.get(externalId));
    }

    /** Your id for a platform id. Empty when the record has none. */
    public Optional<String> external(String id) {
        if (!toExternal.containsKey(id))
            lookup(() -> client.mappings().toExternal(type, id)).ifPresent(m -> put(m.externalId(), m.id()));
        return Optional.ofNullable(toExternal.get(id));
    }

    /** Keeps the ids of a push without another request. */
    public void remember(List<SyncResult> results) {
        for (var r : results) if (r.id() != null) put(r.externalId(), r.id());
    }

    private void put(String externalId, String id) {
        toId.put(externalId, id);
        toExternal.put(id, externalId);
    }

    private static Optional<Mapping> lookup(java.util.function.Supplier<Mapping> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (ObensApiException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
    }
}
