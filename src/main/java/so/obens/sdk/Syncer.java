package so.obens.sdk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Keeps one kind of record in sync with the platform.
 * <ul>
 *   <li>{@link #init} - the first full load, chunked, with a report of what was created / updated / failed</li>
 *   <li>{@link #push} / {@link #pushMany} - send changes as they happen (upsert, so replays are harmless)</li>
 *   <li>{@link #remove} - send a delete</li>
 * </ul>
 * The mapper must return the push body including {@code externalId}.
 */
public final class Syncer<R> {
    public enum Kind {SUB_ACTORS, FACILITIES}

    private final Resource resource;
    private final Kind kind;
    private final Function<R, Map<String, Object>> mapper;
    private final int chunkSize;
    private final Consumer<SyncReport> onProgress;

    Syncer(Resource resource, Kind kind, Function<R, Map<String, Object>> mapper, int chunkSize, Consumer<SyncReport> onProgress) {
        this.resource = resource;
        this.kind = kind;
        this.mapper = mapper;
        this.chunkSize = Math.min(chunkSize, Resource.MAX_BULK);
        this.onProgress = onProgress;
    }

    /** Takes any Iterator, so a big table can be streamed from a cursor without loading it all. */
    public SyncReport init(Iterator<R> records) {
        var report = new SyncReport();
        var batch = new ArrayList<Map<String, Object>>();
        while (records.hasNext()) {
            batch.add(mapped(records.next()));
            if (batch.size() >= chunkSize) flush(report, batch);
        }
        flush(report, batch);
        return report;
    }

    public SyncReport init(Iterable<R> records) {
        return init(records.iterator());
    }

    public SyncResult push(R record) {
        return resource.upsert(mapped(record));
    }

    public SyncReport pushMany(List<R> records) {
        var report = new SyncReport();
        report.add(resource.bulk(records.stream().map(this::mapped).toList(), chunkSize));
        return report;
    }

    public SyncResult remove(R record) {
        var push = new java.util.LinkedHashMap<>(mapped(record));
        if (kind == Kind.FACILITIES) push.put("deleted", true);
        else push.put("status", "DELETED");
        return resource.upsert(push);
    }

    private void flush(SyncReport report, List<Map<String, Object>> batch) {
        if (batch.isEmpty()) return;
        report.add(resource.bulk(batch, chunkSize));
        batch.clear();
        if (onProgress != null) onProgress.accept(report);
    }

    private Map<String, Object> mapped(R record) {
        var push = mapper.apply(record);
        if (push == null || push.get("externalId") == null)
            throw new IllegalArgumentException("The mapper must return a map with an externalId");
        return push;
    }
}
