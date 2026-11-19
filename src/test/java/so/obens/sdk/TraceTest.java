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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TraceTest {
    record Call(String method, String url, String workspace, Map<String, Object> body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger whoami = new AtomicInteger();
    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked") Map<String, Object> body = raw.isBlank() ? null : json.readValue(raw, Map.class);
            var path = exchange.getRequestURI().getRawPath();
            var query = exchange.getRequestURI().getRawQuery();
            var method = exchange.getRequestMethod();
            calls.add(new Call(method, path + (query == null ? "" : "?" + query),
                    exchange.getRequestHeaders().getFirst("X-WORKSPACE-ID"), body));
            int status = 200;
            Object out = null;
            if (path.equals("/integration/whoami")) {
                whoami.incrementAndGet();
                out = Map.of("keyId", "k", "keyName", "n", "accountId", "a1", "workspaceId", "w1");
            } else if (path.equals("/integration/mappings/SUB_ACTOR/external/farmer-1"))
                out = Map.of("type", "SUB_ACTOR", "externalId", "farmer-1", "id", "sa-1");
            else if (path.equals("/integration/mappings/FACILITY/external/orchard-1"))
                out = Map.of("type", "FACILITY", "externalId", "orchard-1", "id", "fa-1");
            else if (path.equals("/integration/mappings/ACCOUNT/external/buyer-1"))
                out = Map.of("type", "ACCOUNT", "externalId", "buyer-1", "id", "ac-9");
            else if (method.equals("PUT") && path.startsWith("/integration/batches/")) {
                if ("bad".equals(body.get("name"))) {
                    status = 400;
                    out = Map.of("message", "QUANTITY_IS_REQUIRED");
                } else
                    out = Map.of("externalId", URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8),
                            "id", "b-1", "outcome", "CREATED");
            } else if (method.equals("PUT") && path.startsWith("/integration/transactions/"))
                out = Map.of("externalId", path.substring(path.lastIndexOf('/') + 1), "id", "t-1", "outcome", "CREATED");
            else if (path.equals("/integration/transactions"))
                out = Map.of("data", List.of(Map.of("id", "t-1", "externalId", "tr-1")), "page", 0, "size", 10, "total", 1);
            else if (path.equals("/integration/batches"))
                out = Map.of("data", List.of(Map.of("id", "b-1", "externalId", "lot-1")), "page", 0, "size", 10, "total", 1);
            else if (path.endsWith("/data"))
                out = List.of(Map.of("id", "f1", "name", "Moisture", "type", "NumericInput", "value", 12));
            else if (path.equals("/integration/batches/schema"))
                out = List.of(Map.of("id", "f1", "name", "Moisture", "type", "NumericInput", "writable", true));
            else status = 404;
            var bytes = out == null ? new byte[0] : json.writeValueAsBytes(out);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ObensClient client() {
        return ObensClient.builder(url, "obk_good").traceUrl(url).maxRetries(0).build();
    }

    private List<Call> puts() {
        return calls.stream().filter(c -> c.method().equals("PUT")).toList();
    }

    @Test
    void batchPushGoesToTraceWithTheWorkspaceAskedOnce() {
        var client = client();
        var r = client.batches().upsert(Map.of("externalId", "lot/1", "name", "Lot",
                "quantity", Map.of("value", 5, "unit", "KG"), "data", Map.of("Moisture", 12)));
        client.batches().upsert(Map.of("externalId", "lot/1", "name", "Lot"));
        assertEquals(SyncResult.Outcome.CREATED, r.outcome());
        var first = puts().get(0);
        assertEquals("/integration/batches/lot%2F1", first.url());
        assertEquals("w1", first.workspace());
        assertFalse(first.body().containsKey("externalId"));
        assertEquals(1, whoami.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void purchaseNamesFarmerBuyerAndFacilitiesByClientIds() {
        var client = client();
        client.transactions().upsert(Map.of("externalId", "buy-1", "elementId", "e1", "subActorExternalId", "farmer-1",
                "date", "2026-02-01", "collected", Map.of("name", "Olives", "quantity", Map.of("value", 100, "unit", "KG"),
                        "facilityExternalIds", List.of("orchard-1"))));
        client.transactions().upsert(Map.of("externalId", "tr-1", "elementId", "e1", "clientExternalId", "buyer-1",
                "supplierId", "a1", "batches", List.of()));
        var purchase = puts().get(0).body();
        var transfer = puts().get(1).body();
        assertEquals("sa-1", purchase.get("subActorId"));
        assertFalse(purchase.containsKey("subActorExternalId"));
        assertEquals(List.of("fa-1"), ((Map<String, Object>) purchase.get("collected")).get("facilityIds"));
        assertEquals("ac-9", transfer.get("clientId"));
        assertEquals("a1", transfer.get("supplierId"));
    }

    @Test
    void bulkKeepsGoingAfterAFailedItem() {
        var results = client().batches().bulk(List.of(Map.of("externalId", "a", "name", "ok"),
                Map.of("externalId", "b", "name", "bad"), Map.of("externalId", "c", "name", "ok")));
        assertEquals(List.of(SyncResult.Outcome.CREATED, SyncResult.Outcome.FAILED, SyncResult.Outcome.CREATED),
                results.stream().map(SyncResult::outcome).toList());
        assertTrue(results.get(1).error().contains("QUANTITY_IS_REQUIRED"));
    }

    @Test
    void schemaReadsTheTraceApi() {
        assertEquals("Moisture", client().batches().schema("pm1").get(0).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsBuildTheirQueryAndReadFormData() {
        var client = client();
        var page = client.transactions().list("e1", null, "BUY", "2026-01-01", null, "olive oil", null, 5);
        assertEquals("tr-1", ((Map<String, Object>) ((List<Object>) page.get("data")).get(0)).get("externalId"));
        assertTrue(calls.stream().anyMatch(c -> c.url().equals(
                "/integration/transactions?element-id=e1&type=BUY&start=2026-01-01&search=olive%20oil&size=5")));
        assertEquals(12, client.batches().data("lot/1", "sa-1").get(0).get("value"));
        assertTrue(calls.stream().anyMatch(c -> c.url().equals("/integration/batches/lot%2F1/data?owner-id=sa-1")));
        assertEquals("Moisture", client.transactions().data("tr-1", null).get(0).get("name"));
        assertEquals(1, ((Number) client.batches().list("e1").get("total")).intValue());
    }
}
