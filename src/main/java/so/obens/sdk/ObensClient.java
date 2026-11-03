package so.obens.sdk;

import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static so.obens.sdk.Resource.encode;

/**
 * Entry point.
 * <pre>{@code
 * var client = ObensClient.builder("https://api.obens.so/v2/core", System.getenv("OBENS_API_KEY")).build();
 * }</pre>
 */
public final class ObensClient {
    private final Builder settings;
    private final Http http;
    private final Resource subActors;
    private final Resource facilities;
    private final Accounts accounts = new Accounts();
    private final Mappings mappings = new Mappings();

    private ObensClient(Builder settings, Http http) {
        this.settings = settings;
        this.http = http;
        this.subActors = new Resource(http, "/integration/sub-actors");
        this.facilities = new Resource(http, "/integration/facilities");
    }

    public static Builder builder(String baseUrl, String apiKey) {
        return new Builder(baseUrl, apiKey);
    }

    public Resource subActors() {
        return subActors;
    }

    public Resource facilities() {
        return facilities;
    }

    public Accounts accounts() {
        return accounts;
    }

    public Mappings mappings() {
        return mappings;
    }

    /**
     * A client acting as an account delegated to the key's account (see {@code accounts().accessible()}): farmers,
     * facilities, links and id tables are then those of that account. Its ids are separate from the key account's.
     */
    public ObensClient forAccount(String accountId) {
        return settings.accountId(accountId).build();
    }

    /** Who the key is. Handy as a connection check. */
    public Whoami whoami() {
        return http.request("GET", "/integration/whoami", null, new TypeReference<>() {
        });
    }

    /** The lists farmers can be created in; you need one {@code id}. */
    public List<ElementRef> elements() {
        return http.request("GET", "/integration/elements", null, new TypeReference<>() {
        });
    }

    /** Custom fields of the farmers in a list; use a field's name (or staticId) as a key of {@code data}. */
    public List<java.util.Map<String, Object>> subActorSchema(String elementId) {
        return http.request("GET", "/integration/sub-actors/schema?element-id=" + Resource.encode(elementId), null,
                new TypeReference<>() {
                });
    }

    /** Id table for one kind. Call {@link IdMap#load()} to fill it. */
    public IdMap idMap(MappingType type) {
        return new IdMap(this, type);
    }

    public <R> Syncer<R> syncer(Syncer.Kind kind, Function<R, Map<String, Object>> mapper) {
        return syncer(kind, mapper, 200, null);
    }

    public <R> Syncer<R> syncer(Syncer.Kind kind, Function<R, Map<String, Object>> mapper, int chunkSize, Consumer<SyncReport> onProgress) {
        var resource = kind == Syncer.Kind.SUB_ACTORS ? subActors : facilities;
        return new Syncer<>(resource, kind, mapper, chunkSize, onProgress);
    }

    public final class Accounts {
        /** The key's own account first, then the accounts delegated to it. */
        public List<AccountRef> accessible() {
            return http.request("GET", "/integration/accounts", null, new TypeReference<>() {
            });
        }

        /** Links your id to an account that already exists on the platform. */
        public Mapping link(String externalId, String accountId) {
            return http.request("PUT", "/integration/accounts/" + encode(externalId), Map.of("accountId", accountId),
                    new TypeReference<>() {
                    });
        }

        public void unlink(String externalId) {
            http.request("DELETE", "/integration/accounts/" + encode(externalId), null, new TypeReference<Void>() {
            });
        }
    }

    public final class Mappings {
        public List<Mapping> list(MappingType type) {
            return http.request("GET", "/integration/mappings/" + type, null, new TypeReference<>() {
            });
        }

        public Mapping toInternal(MappingType type, String externalId) {
            return http.request("GET", "/integration/mappings/" + type + "/external/" + encode(externalId), null,
                    new TypeReference<>() {
                    });
        }

        public Mapping toExternal(MappingType type, String id) {
            return http.request("GET", "/integration/mappings/" + type + "/internal/" + encode(id), null,
                    new TypeReference<>() {
                    });
        }
    }

    public static final class Builder {
        private final String baseUrl;
        private final String apiKey;
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private Duration retryDelay = Duration.ofMillis(500);
        private String accountId;

        private Builder(String baseUrl, String apiKey) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        /** Act as this delegated account instead of the key's own. */
        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public ObensClient build() {
            return new ObensClient(this, new Http(baseUrl, apiKey, timeout, maxRetries, retryDelay, accountId));
        }
    }
}
