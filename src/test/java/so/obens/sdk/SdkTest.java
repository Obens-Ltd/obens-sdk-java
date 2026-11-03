package so.obens.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SdkTest {
    record Call(String method, String url, String key, Map<String, Object> body) {
    }

    record Farmer(String id, String first, String last, String village) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, String> subActors = new LinkedHashMap<>();
    private final AtomicInteger failFirst = new AtomicInteger();
    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> body = raw.isBlank() ? null : json.readValue(raw, Map.class);
                var path = exchange.getRequestURI().getRawPath();
                calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()), exchange.getRequestHeaders().getFirst("X-API-KEY"), body));
                var method = exchange.getRequestMethod();
                var key = exchange.getRequestHeaders().getFirst("X-API-KEY");
                int status = 200;
                Object out = null;
                if (!"obk_good".equals(key)) status = 401;
                else if (failFirst.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    exchange.getResponseHeaders().add("Retry-After", "0");
                    status = 503;
                } else if (method.equals("PUT") && path.startsWith("/integration/sub-actors/")) {
                    var ext = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                    out = upsert(ext, body);
                } else if (method.equals("POST") && path.equals("/integration/sub-actors/bulk")) {
                    var results = new ArrayList<Map<String, Object>>();
                    for (var item : (List<Map<String, Object>>) body.get("items"))
                        results.add("bad".equals(item.get("name"))
                                ? Map.of("externalId", item.get("externalId"), "outcome", "FAILED", "error", "nope")
                                : upsert((String) item.get("externalId"), item));
                    out = results;
                } else if (path.equals("/integration/sub-actors/schema")) {
                    out = List.of(Map.of("id", "f1", "name", "Crop", "type", "TextInput", "writable", true));
                } else if (path.equals("/integration/mappings/SUB_ACTOR")) {
                    var list = new ArrayList<Map<String, Object>>();
                    subActors.forEach((k, v) -> list.add(Map.of("type", "SUB_ACTOR", "externalId", k, "id", v)));
                    out = list;
                } else if (path.startsWith("/integration/mappings/SUB_ACTOR/external/")) {
                    var ext = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                    if (subActors.containsKey(ext))
                        out = Map.of("type", "SUB_ACTOR", "externalId", ext, "id", subActors.get(ext));
                    else status = 404;
                } else status = 404;
                var bytes = out == null ? new byte[0] : json.writeValueAsBytes(out);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> upsert(String externalId, Map<String, Object> body) {
        boolean created = !subActors.containsKey(externalId);
        if (created) subActors.put(externalId, "id-" + (subActors.size() + 1));
        return Map.of("externalId", externalId, "id", subActors.get(externalId), "outcome", created ? "CREATED" : "UPDATED");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ObensClient client(String key) {
        return ObensClient.builder(url, key).retryDelay(Duration.ofMillis(1)).build();
    }

    private final Mapper<Farmer> mapper = Mapper.<Farmer>builder()
            .field("externalId", Farmer::id)
            .field("name", f -> f.first() + " " + f.last())
            .field("info.name", Farmer::village)
            .field("ref", f -> null)
            .constant("type", "FARMER")
            .build();

    @Test
    void mapperBuildsNestedValuesAndSkipsNulls() {
        assertEquals(Map.of("externalId", "7", "name", "A B", "info", Map.of("name", "V"), "type", "FARMER"),
                mapper.apply(new Farmer("7", "A", "B", "V")));
    }

    @Test
    void subActorSchemaListsTheCustomFields() {
        var fields = client("obk_good").subActorSchema("el 1");
        assertEquals("Crop", fields.get(0).get("name"));
        assertTrue(calls.get(calls.size() - 1).url().contains("schema?element-id=el%201"));
    }

    @Test
    void upsertSendsKeyAndEncodesExternalId() {
        var result = client("obk_good").subActors().upsert(Map.of("externalId", "a/b c", "name", "X"));
        assertEquals(SyncResult.Outcome.CREATED, result.outcome());
        assertEquals("/integration/sub-actors/a%2Fb%20c", calls.get(0).url());
        assertEquals("obk_good", calls.get(0).key());
    }

    @Test
    void wrongKeyIs401WithoutRetries() {
        var e = assertThrows(ObensApiException.class, () -> client("nope").whoami());
        assertEquals(401, e.status());
        assertEquals(1, calls.size());
    }

    @Test
    void transient503IsRetried() {
        failFirst.set(2);
        var result = client("obk_good").subActors().upsert(Map.of("externalId", "1", "name", "X"));
        assertEquals(SyncResult.Outcome.CREATED, result.outcome());
        assertEquals(3, calls.size());
    }

    @Test
    void initChunksReportsPerItemAndReplayUpdates() {
        var rows = new ArrayList<Farmer>();
        for (int i = 0; i < 5; i++) rows.add(new Farmer("f" + i, i == 3 ? "bad" : "n" + i, "", "v"));
        // "bad" is detected by the fake through the name
        Mapper<Farmer> m = Mapper.<Farmer>builder().field("externalId", Farmer::id).field("name", Farmer::first).build();
        var farmers = client("obk_good").syncer(Syncer.Kind.SUB_ACTORS, m, 2, null);
        var first = farmers.init(rows);
        assertEquals(3, calls.stream().filter(c -> c.url().endsWith("/bulk")).count());
        assertEquals(5, first.total());
        assertEquals(4, first.created());
        assertEquals(1, first.failed());
        assertEquals("f3", first.failures().get(0).externalId());
        var replay = farmers.init(rows.stream().filter(r -> !r.first().equals("bad")).toList());
        assertEquals(0, replay.created());
        assertEquals(4, replay.updated());
    }

    @Test
    void removeSendsDeletedStatus() {
        client("obk_good").syncer(Syncer.Kind.SUB_ACTORS, mapper).remove(new Farmer("z", "A", "B", "V"));
        assertEquals("DELETED", calls.get(0).body().get("status"));
    }

    @Test
    void idMapTranslatesBothWaysAndTreats404AsUnknown() {
        var client = client("obk_good");
        client.subActors().upsert(Map.of("externalId", "A", "name", "x"));
        var ids = client.idMap(MappingType.SUB_ACTOR).load();
        assertEquals("id-1", ids.internal("A").orElseThrow());
        assertEquals("A", ids.external("id-1").orElseThrow());
        assertTrue(ids.internal("missing").isEmpty());
    }

    @Test
    void forAccountScopesCallsToADelegatedAccount() {
        var client = client("obk_good");
        var delegated = client.forAccount("acc 1");
        delegated.subActors().upsert(Map.of("externalId", "1", "name", "X"));
        client.subActors().upsert(Map.of("externalId", "2", "name", "Y"));
        delegated.idMap(MappingType.SUB_ACTOR).load();
        assertEquals("/integration/sub-actors/1?account-id=acc%201", calls.get(0).url());
        assertEquals("/integration/sub-actors/2", calls.get(1).url());
        assertEquals("/integration/mappings/SUB_ACTOR?account-id=acc%201", calls.get(2).url());
    }
}
