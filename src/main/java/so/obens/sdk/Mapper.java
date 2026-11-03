package so.obens.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns <b>your</b> record into what the platform expects. A dotted key builds a nested object
 * ({@code "info.name"} -> {@code {info: {name: ...}}}) and a {@code null} value is left out, so an update never
 * blanks a field.
 *
 * <pre>{@code
 * Mapper<Farmer> mapper = Mapper.<Farmer>builder()
 *     .field("externalId", Farmer::id)
 *     .field("name", f -> f.firstName() + " " + f.lastName())
 *     .constant("elementId", FARMERS_ELEMENT)
 *     .constant("type", "FARMER")
 *     .build();
 * }</pre>
 */
public final class Mapper<R> implements Function<R, Map<String, Object>> {
    private final Map<String, Function<R, ?>> fields;

    private Mapper(Map<String, Function<R, ?>> fields) {
        this.fields = fields;
    }

    public static <R> Builder<R> builder() {
        return new Builder<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(R record) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            var value = entry.getValue().apply(record);
            if (value == null) continue;
            var parts = entry.getKey().split("\\.");
            var target = out;
            for (int i = 0; i < parts.length - 1; i++)
                target = (Map<String, Object>) target.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            target.put(parts[parts.length - 1], value);
        }
        return out;
    }

    public static final class Builder<R> {
        private final Map<String, Function<R, ?>> fields = new LinkedHashMap<>();

        public Builder<R> field(String key, Function<R, ?> source) {
            fields.put(key, source);
            return this;
        }

        public Builder<R> constant(String key, Object value) {
            fields.put(key, r -> value);
            return this;
        }

        public Mapper<R> build() {
            return new Mapper<>(new LinkedHashMap<>(fields));
        }
    }
}
