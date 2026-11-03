package so.obens.sdk;

import java.util.ArrayList;
import java.util.List;

/** The outcome of a load: counts, plus the records that failed and why. */
public final class SyncReport {
    private int total;
    private int created;
    private int updated;
    private int failed;
    private final List<SyncResult> failures = new ArrayList<>();
    private final List<SyncResult> results = new ArrayList<>();

    void add(List<SyncResult> batch) {
        for (var r : batch) {
            total++;
            switch (r.outcome()) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case FAILED -> {
                    failed++;
                    failures.add(r);
                }
            }
            results.add(r);
        }
    }

    public int total() {
        return total;
    }

    public int created() {
        return created;
    }

    public int updated() {
        return updated;
    }

    public int failed() {
        return failed;
    }

    public List<SyncResult> failures() {
        return failures;
    }

    public List<SyncResult> results() {
        return results;
    }
}
