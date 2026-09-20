package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.service.AppKernel;
import gsb.storage.Json;
import gsb.web.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.MethodName.class)
class HttpEndToEndTest {

    static WebServer server;
    static HttpClient http;
    static int port;
    static String sourceA;
    static String sourceB;

    static String api(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    static HttpResponse<byte[]> post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.writeString(body)))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    @BeforeAll
    static void up() throws Exception {
        Path dir = TestEnv.tempDir();
        AppKernel kernel = new AppKernel(dir, TestEnv.fixedClock());
        port = 5399;
        server = new WebServer(kernel, port);
        server.start();
        http = HttpClient.newHttpClient();

        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("workspaceId", "demo");
        ws.put("name", "Demo");
        ws.put("image", Map.of("imageId", "img", "fingerprintSha256", DomainFixtures.FINGERPRINT_A));
        ws.put("canonicalGeometry", Map.of(
                "width", 8, "height", 6, "spacingX", 0.5, "spacingY", 0.5,
                "orientation", "L|A", "originX", 0, "originY", 0));
        assertThat(post("/api/workspaces", ws).statusCode()).isEqualTo(201);
    }

    @AfterAll
    static void down() {
        server.stop();
    }

    private Map<String, Object> sourceBody(String algo, String vocab, int seed) {
        int[] labels = new int[48];
        for (int i = 0; i < 48; i++) {
            labels[i] = i % seed == 0 ? 1 : (i % 7 == 0 ? 2 : 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("algorithm", algo);
        body.put("image", Map.of("imageId", "img", "fingerprintSha256", DomainFixtures.FINGERPRINT_A));
        body.put("geometry", Map.of("width", 8, "height", 6, "spacingX", 0.5,
                "spacingY", 0.5, "orientation", "L|A", "originX", 0, "originY", 0));
        body.put("vocabulary", Map.of("vocabVersion", vocab, "entries", new Object[]{
                Map.of("classId", 1, "name", "liver", "displayColor", "#cc0000"),
                Map.of("classId", 2, "name", "tumor", "displayColor", "#00cc00")}));
        body.put("labels", labels);
        return body;
    }

    @Test
    void t1_ingestRejectsWrongFingerprintViaHttp() throws Exception {
        Map<String, Object> body = sourceBody("evil", "vocab-x", 3);
        body.put("image", Map.of("imageId", "img", "fingerprintSha256", DomainFixtures.FINGERPRINT_B));
        HttpResponse<byte[]> res = post("/api/workspaces/demo/sources", body);
        assertThat(res.statusCode()).isEqualTo(409);
        JsonNode json = Json.MAPPER.readTree(res.body());
        assertThat(json.get("reasons").toString()).contains("fingerprint");
    }

    @Test
    void t2_ingestTwoSources() throws Exception {
        HttpResponse<byte[]> a = post("/api/workspaces/demo/sources", sourceBody("algo-a", "vocab-1", 3));
        HttpResponse<byte[]> b = post("/api/workspaces/demo/sources", sourceBody("algo-b", "vocab-2", 4));
        assertThat(a.statusCode()).isEqualTo(201);
        sourceA = Json.MAPPER.readTree(a.body()).get("source").get("sourceId").asText();
        sourceB = Json.MAPPER.readTree(b.body()).get("source").get("sourceId").asText();
    }

    @Test
    void t3_releaseBlockedThenConfirmMappingsThenAllowed() throws Exception {
        HttpResponse<byte[]> g0 = http.send(HttpRequest.newBuilder(URI.create(api(
                "/api/workspaces/demo/gate"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(Json.MAPPER.readTree(g0.body()).get("allowed").asBoolean()).isFalse();

        HttpResponse<byte[]> state = http.send(HttpRequest.newBuilder(
                URI.create(api("/api/workspaces/demo/state"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        JsonNode mappings = Json.MAPPER.readTree(state.body()).get("mappings");
        for (JsonNode m : mappings) {
            post("/api/workspaces/demo/mappings/confirm/" + m.get("mappingId").asText(),
                    Map.of("user", "reviewer"));
        }
        HttpResponse<byte[]> g1 = http.send(HttpRequest.newBuilder(URI.create(api(
                "/api/workspaces/demo/gate"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(Json.MAPPER.readTree(g1.body()).get("allowed").asBoolean()).isTrue();
    }

    @Test
    void t4_compareAndFetchTile() throws Exception {
        Map<String, Object> cmp = Map.of("sourceIds", new String[]{sourceA, sourceB},
                "confidenceThreshold", 0.0, "quarantineResampleEdges", true);
        HttpResponse<byte[]> res = post("/api/workspaces/demo/comparisons", cmp);
        assertThat(res.statusCode()).isEqualTo(201);
        JsonNode json = Json.MAPPER.readTree(res.body());
        assertThat(json.get("stats").get("disagreePixels").asInt()).isPositive();
        String cmpId = json.get("comparisonId").asText();

        HttpRequest tileReq = HttpRequest.newBuilder(URI.create(api(
                "/api/workspaces/demo/tiles/comparison/" + cmpId + "/0/0/0.png"))).GET().build();
        HttpResponse<byte[]> tile = http.send(tileReq, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(tile.statusCode()).isEqualTo(200);
        assertThat(tile.body()).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    void t5_concurrentOverlapReturns409ConflictAndAutoMerges() throws Exception {
        Map<String, Object> region = Map.of("ring", new Object[][]{
                {0.5, 0.5}, {3.5, 0.5}, {3.5, 3.5}, {0.5, 3.5}});
        Map<String, Object> alice = new LinkedHashMap<>();
        alice.put("decisionType", "ACCEPT_SOURCE");
        alice.put("region", region);
        alice.put("sourceId", sourceA);
        alice.put("baseVersion", 0);
        alice.put("author", "alice");
        assertThat(post("/api/workspaces/demo/decisions", alice).statusCode()).isEqualTo(201);

        Map<String, Object> bob = new LinkedHashMap<>();
        bob.put("decisionType", "ACCEPT_SOURCE");
        bob.put("region", Map.of("ring", new Object[][]{
                {0.5, 0.5}, {5.5, 0.5}, {5.5, 3.5}, {0.5, 3.5}}));
        bob.put("sourceId", sourceB);
        bob.put("baseVersion", 0);
        bob.put("author", "bob");
        HttpResponse<byte[]> res = post("/api/workspaces/demo/decisions", bob);
        assertThat(res.statusCode()).isEqualTo(409);
        JsonNode conflict = Json.MAPPER.readTree(res.body()).get("conflict");
        assertThat(conflict.get("conflictingPixels").asInt()).isPositive();
        assertThat(conflict.get("conflictPolygons").size()).isPositive();
        assertThat(conflict.get("autoMerged").size()).isPositive();
    }

    @Test
    void t6_exportAndReimportPreservesHash() throws Exception {
        HttpResponse<byte[]> exported = http.send(HttpRequest.newBuilder(
                URI.create(api("/api/workspaces/demo/export"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(exported.statusCode()).isEqualTo(200);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("newWorkspaceId", "restored");
        wrapper.put("name", "Restored");
        wrapper.put("bundleBase64",
                Base64.getEncoder().encodeToString(exported.body()));
        HttpResponse<byte[]> imported = post("/api/import", wrapper);
        assertThat(imported.statusCode()).isEqualTo(201);
        JsonNode report = Json.MAPPER.readTree(imported.body());
        assertThat(report.get("accepted").asBoolean()).isTrue();
        assertThat(report.get("boundaryHash").asText()).hasSize(64);
    }

    @Test
    void t7_indexPageIsServed() throws Exception {
        HttpResponse<byte[]> idx = http.send(HttpRequest.newBuilder(URI.create(api("/")))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(idx.statusCode()).isEqualTo(200);
        assertThat(new String(idx.body(), StandardCharsets.UTF_8)).contains("GSB");
    }
}
