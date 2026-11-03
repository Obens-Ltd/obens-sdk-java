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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Biolife (e-pineA) pushes {@code { type, action, data }} events from its field app. This shows how those records go
 * through the SDK: the producer and the field become farmers and facilities, keyed by the app's own {@code appId}.
 */
class BiolifeTest {
    static final String FARMERS_ELEMENT = "66f000000000000000000001";

    record Event(String type, String action, Map<String, Object> data) {
        Event withAction(String action) {
            return new Event(type, action, data);
        }

        Event with(String key, Object value) {
            var copy = new LinkedHashMap<>(data);
            copy.put(key, value);
            return new Event(type, action, copy);
        }

        String text(String key) {
            return (String) data.get(key);
        }
    }

    record Point(double lat, double lng) {
    }

    static Event producteur(int n) {
        return new Event("producteur", "create", new LinkedHashMap<>(Map.of(
                "appId", "prd-" + n, "firstName", "Afi", "lastName", "Houngbo", "phone", "+2299700000" + n,
                "gender", "F", "birthday", "1985-04-12", "zone", "ZON_3", "address", "Allada, Sékou")));
    }

    static Event champ(int n, String producteurId, List<Point> outline) {
        return new Event("fields", "create", new LinkedHashMap<>(Map.of(
                "appId", "chp-" + n, "producteurId", producteurId, "libelle", "Champ " + n,
                "plantingDate", "2025-11-03", "latitude", 6.6512, "longitude", 2.1534,
                "coordinates", outline)));
    }

    static final List<Point> TRACED = List.of(new Point(6.65, 2.15), new Point(6.65, 2.16), new Point(6.66, 2.16));

    // the phone is what identifies a producer for them; the appId is what makes a replay harmless
    final Mapper<Event> mapProducer = Mapper.<Event>builder()
            .field("externalId", e -> e.text("appId"))
            .field("name", e -> e.text("firstName") + " " + e.text("lastName"))
            .field("ref", e -> e.text("phone"))
            .field("address", e -> e.text("address"))
            .field("gender", e -> Map.of("M", "MALE", "F", "FEMALE").get(e.text("gender")))
            .constant("type", "FARMER")
            .constant("elementId", FARMERS_ELEMENT)
            .build();

    // a polygon is a closed GeoJSON ring of [lng, lat]; a field drawn with fewer than 3 points is only a position
    @SuppressWarnings("unchecked")
    static Map<String, Object> coordinates(Event e) {
        var outline = (List<Point>) e.data().get("coordinates");
        if (outline != null && outline.size() >= 3) {
            var ring = new ArrayList<List<Double>>();
            for (var p : outline) ring.add(List.of(p.lng(), p.lat()));
            ring.add(ring.get(0));
            return Map.of("type__", "Area", "type", "FeatureCollection", "features", List.of(
                    Map.of("type", "Feature", "geometry", Map.of("type", "Polygon", "coordinates", List.of(ring)))));
        }
        return Map.of("type__", "Point", "latitude", e.data().get("latitude"), "longitude", e.data().get("longitude"));
    }

    final Mapper<Event> mapField = Mapper.<Event>builder()
            .field("externalId", e -> e.text("appId"))
            .field("info.name", e -> e.text("libelle"))
            .field("info.description", e -> "Planté le " + e.text("plantingDate"))
            .field("coordinates", BiolifeTest::coordinates)
            .build();

    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
    private final Set<String> known = new HashSet<>();
    private HttpServer server;
    private ObensClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> body = json.readValue(raw, Map.class);
                var path = exchange.getRequestURI().getRawPath();
                Object out;
                if (exchange.getRequestMethod().equals("PUT")) {
                    var ext = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                    var item = new LinkedHashMap<>(body);
                    item.put("externalId", ext);
                    out = upsert(item);
                } else {
                    var results = new ArrayList<Map<String, Object>>();
                    for (var item : (List<Map<String, Object>>) body.get("items")) results.add(upsert(item));
                    out = results;
                }
                var bytes = json.writeValueAsBytes(out);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = ObensClient.builder("http://127.0.0.1:" + server.getAddress().getPort(), "obk_good")
                .retryDelay(Duration.ofMillis(1)).build();
    }

    private synchronized Map<String, Object> upsert(Map<String, Object> item) {
        bodies.add(item);
        var externalId = (String) item.get("externalId");
        boolean created = known.add(externalId);
        return Map.of("externalId", externalId, "id", "id-" + externalId, "outcome", created ? "CREATED" : "UPDATED");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void producersWithTheSameNameStayTwoFarmersAndAReplayUpdates() {
        var farmers = client.syncer(Syncer.Kind.SUB_ACTORS, mapProducer);
        var events = List.of(producteur(1), producteur(2), producteur(3).with("gender", "X"));

        var first = farmers.init(events);
        assertEquals(3, first.total());
        assertEquals(3, first.created());
        assertEquals(0, first.failed());
        assertEquals(Map.of(
                "externalId", "prd-1", "name", "Afi Houngbo", "ref", "+22997000001", "address", "Allada, Sékou",
                "gender", "FEMALE", "type", "FARMER", "elementId", FARMERS_ELEMENT), bodies.get(0));
        assertEquals(bodies.get(0).get("name"), bodies.get(1).get("name"));
        assertNotEquals(bodies.get(0).get("ref"), bodies.get(1).get("ref"));
        assertFalse(bodies.get(2).containsKey("gender"), "an unknown gender is left out, not sent as null");

        var replay = farmers.init(events);
        assertEquals(0, replay.created());
        assertEquals(3, replay.updated());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTracedFieldBecomesAClosedPolygonAnUntracedOneAPoint() {
        var facilities = client.syncer(Syncer.Kind.FACILITIES, mapField);

        var report = facilities.init(List.of(
                champ(1, "prd-1", TRACED),
                champ(2, "prd-1", List.of(new Point(6.6, 2.1)))));
        assertEquals(2, report.created());
        assertEquals(0, report.failed());

        var traced = bodies.get(0);
        assertEquals("Champ 1", ((Map<String, Object>) traced.get("info")).get("name"));
        var feature = ((List<Map<String, Object>>) ((Map<String, Object>) traced.get("coordinates")).get("features")).get(0);
        var geometry = (Map<String, Object>) feature.get("geometry");
        assertEquals("Polygon", geometry.get("type"));
        var ring = ((List<List<List<Double>>>) geometry.get("coordinates")).get(0);
        assertEquals(List.of(2.15, 6.65), ring.get(0), "GeoJSON order is [longitude, latitude]");
        assertEquals(ring.get(0), ring.get(ring.size() - 1), "the ring is closed");
        assertEquals(4, ring.size());
        assertEquals(Map.of("type__", "Point", "latitude", 6.6512, "longitude", 2.1534), bodies.get(1).get("coordinates"));
    }

    @Test
    void createAndUpdatePushDeleteRemovesAndOtherRecordTypesAreNotOurs() {
        var farmers = client.syncer(Syncer.Kind.SUB_ACTORS, mapProducer);
        var facilities = client.syncer(Syncer.Kind.FACILITIES, mapField);

        // what the integration does with the stream the app sends; null when the type has no counterpart yet
        java.util.function.Function<Event, SyncResult> handle = event -> {
            Syncer<Event> target = switch (event.type()) {
                case "producteur" -> farmers;
                case "fields" -> facilities;
                default -> null; // tif, phytosanitaire, conditionnement
            };
            if (target == null) return null;
            return event.action().equals("delete") ? target.remove(event) : target.push(event);
        };

        assertEquals(SyncResult.Outcome.CREATED, handle.apply(producteur(1)).outcome());
        assertEquals(SyncResult.Outcome.UPDATED,
                handle.apply(producteur(1).with("lastName", "Houngbo-Dossou").withAction("update")).outcome());
        assertEquals("Afi Houngbo-Dossou", bodies.get(bodies.size() - 1).get("name"));

        handle.apply(producteur(1).withAction("delete"));
        assertEquals("DELETED", bodies.get(bodies.size() - 1).get("status"));
        handle.apply(champ(1, "prd-1", TRACED).withAction("delete"));
        assertEquals(true, bodies.get(bodies.size() - 1).get("deleted"));

        int before = bodies.size();
        assertNull(handle.apply(new Event("tif", "create", Map.of("appId", "tif-1"))));
        assertEquals(before, bodies.size());
    }
}
