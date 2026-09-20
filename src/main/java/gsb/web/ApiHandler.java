package gsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gsb.geom.Geometry;
import gsb.model.ComparisonParams;
import gsb.model.ComparisonResult;
import gsb.model.Decision;
import gsb.model.DecisionType;
import gsb.model.ImageRef;
import gsb.model.Polygon;
import gsb.model.ReleaseSnapshot;
import gsb.model.VocabEntry;
import gsb.model.Vocabulary;
import gsb.service.AppKernel;
import gsb.service.ComparisonService;
import gsb.service.ConsensusService;
import gsb.service.ExportService;
import gsb.service.IngestRequest;
import gsb.service.IngestResult;
import gsb.service.MappingService;
import gsb.service.ReleaseService;
import gsb.service.SubmitDecisionRequest;
import gsb.service.TileService;
import gsb.service.ValidationException;
import gsb.service.WorkspaceService;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API dispatcher. Pure JDK HTTP server, no external framework or network dependency. */
public final class ApiHandler implements HttpHandler {
    private final AppKernel kernel;
    private final ComparisonService comparisons = new ComparisonService();
    private final ConsensusService consensus = new ConsensusService();
    private final MappingService mappings = new MappingService();
    private final TileService tiles = new TileService();
    private final ExportService exporter = new ExportService();
    private final ReleaseService releaseService = new ReleaseService();

    public ApiHandler(AppKernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (ValidationException e) {
            int status = switch (e.code) {
                case "WORKSPACE_MISSING", "SOURCE_MISSING", "MAPPING_MISSING", "EVENT_MISSING" ->
                        HttpURLConnection.HTTP_NOT_FOUND;
                case "FINGERPRINT_CONFLICT", "INGEST_REJECTED", "RESAMPLE_REQUIRED",
                        "DECISION_REJECTED", "RELEASE_BLOCKED", "BUNDLE_TAMPERED",
                        "BUNDLE_INCOMPLETE", "BUNDLE_CORRUPT", "VERSION_AHEAD",
                        "UNDO_OF_UNDO", "EVENT_INACTIVE", "EVENT_ACTIVE" ->
                        HttpURLConnection.HTTP_CONFLICT;
                default -> HttpURLConnection.HTTP_BAD_REQUEST;
            };
            HttpSupport.error(ex, status, e.code, e.getMessage(), e.reasons);
        } catch (IllegalArgumentException e) {
            HttpSupport.error(ex, HttpURLConnection.HTTP_BAD_REQUEST, "BAD_REQUEST",
                    e.getMessage(), List.of());
        } catch (Exception e) {
            HttpSupport.error(ex, HttpURLConnection.HTTP_INTERNAL_ERROR, "INTERNAL",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        URI uri = ex.getRequestURI();
        String path = uri.getPath();
        String[] parts = path.split("/");
        // "" / "api" / "workspaces" [ / wsId / resource / ... ]
        if (parts.length >= 2 && parts[1].equals("api")) {
            if (parts.length == 3 && parts[2].equals("workspaces")
                    && method.equals("POST")) {
                createWorkspace(ex);
                return;
            }
            if (parts.length == 3 && parts[2].equals("workspaces")
                    && method.equals("GET")) {
                listWorkspaces(ex);
                return;
            }
            if (parts.length >= 5 && parts[2].equals("workspaces")) {
                String wsId = parts[3];
                String resource = parts[4];
                WorkspaceService svc = kernel.get(wsId);
                dispatchWorkspace(ex, svc, resource, parts);
                return;
            }
        }
        HttpSupport.error(ex, HttpURLConnection.HTTP_NOT_FOUND, "NOT_FOUND",
                "no route for " + method + " " + path, List.of());
    }

    private void dispatchWorkspace(HttpExchange ex, WorkspaceService svc, String resource,
                                   String[] parts) throws IOException {
        String method = ex.getRequestMethod();
        switch (resource) {
            case "sources" -> {
                if (method.equals("POST")) {
                    ingest(svc, ex);
                } else {
                    listSources(svc, ex);
                }
            }
            case "mappings" -> mappingsRoute(svc, ex, parts);
            case "comparisons" -> comparisonsRoute(svc, ex, parts);
            case "decisions" -> decisionsRoute(svc, ex, parts);
            case "releases" -> releasesRoute(svc, ex, parts);
            case "export" -> {
                if (method.equals("GET")) {
                    export(svc, ex);
                } else {
                    throw methodNotAllowed(method);
                }
            }
            case "tiles" -> tilesRoute(svc, ex, parts);
            case "state" -> state(svc, ex);
            case "gate" -> gate(svc, ex);
            default -> HttpSupport.error(ex, HttpURLConnection.HTTP_NOT_FOUND, "NOT_FOUND",
                    "unknown resource " + resource, List.of());
        }
    }

    private static ValidationException methodNotAllowed(String method) {
        return ValidationException.of("METHOD_NOT_ALLOWED", method + " not allowed here");
    }

    // ---------------- workspace ----------------

    private void listWorkspaces(HttpExchange ex) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : kernel.workspaceIds()) {
            WorkspaceService svc = kernel.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspaceId", svc.workspace().workspaceId());
            row.put("name", svc.workspace().name());
            row.put("imageFingerprint", svc.workspace().image().fingerprintSha256());
            row.put("eventVersion", svc.workspace().eventVersion());
            row.put("sources", svc.workspace().sources().size());
            row.put("recoveryNote", svc.recoveryNote());
            out.add(row);
        }
        HttpSupport.writeJson(ex, 200, Map.of("workspaces", out));
    }

    private void createWorkspace(HttpExchange ex) throws IOException {
        JsonNode n = HttpSupport.readJson(ex);
        String id = HttpSupport.required(n, "workspaceId");
        String name = n.path("name").asText(id);
        JsonNode imageNode = n.path("image");
        ImageRef image = new ImageRef(HttpSupport.required(imageNode, "imageId"),
                HttpSupport.required(imageNode, "fingerprintSha256"));
        Geometry geom = JsonCodecs.geometry(n.path("canonicalGeometry"));
        WorkspaceService svc = kernel.create(id, name, image, geom);
        HttpSupport.writeJson(ex, 201, summary(svc));
    }

    private void state(WorkspaceService svc, HttpExchange ex) throws IOException {
        HttpSupport.writeJson(ex, 200, summary(svc));
    }

    // ---------------- sources ----------------

    private void listSources(WorkspaceService svc, HttpExchange ex) throws IOException {
        HttpSupport.writeJson(ex, 200, Map.of("sources", svc.workspace().sources().values()));
    }

    private void ingest(WorkspaceService svc, HttpExchange ex) throws IOException {
        JsonNode n = HttpSupport.readJson(ex);
        JsonNode imageNode = n.path("image");
        ImageRef image = new ImageRef(HttpSupport.required(imageNode, "imageId"),
                HttpSupport.required(imageNode, "fingerprintSha256"));
        Geometry geometry = JsonCodecs.geometry(n.path("geometry"));
        Vocabulary vocab = JsonCodecs.vocabulary(n.path("vocabulary"));
        int width = n.path("width").asInt(geometry.width());
        int height = n.path("height").asInt(geometry.height());
        int[] labels = JsonCodecs.intArray(n, "labels");
        double[] confidence = JsonCodecs.doubleArray(n, "confidence");
        String revisionOf = n.path("revisionOf").isMissingNode() || n.path("revisionOf").isNull()
                ? null : n.path("revisionOf").asText();
        boolean allowResample = n.path("allowResample").asBoolean(false);

        gsb.service.IngestRequest req = new IngestRequest(
                HttpSupport.required(n, "algorithm"), revisionOf, image, geometry, vocab,
                width, height, labels, confidence, allowResample);
        IngestResult result = new gsb.service.IngestService().ingest(svc, req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", result.source());
        body.put("resampled", result.resampled());
        body.put("warnings", result.warnings());
        body.put("proposedMappings",
                svc.workspace().mappings().values().stream()
                        .filter(m -> m.sourceId().equals(result.source().sourceId())).toList());
        HttpSupport.writeJson(ex, 201, body);
    }

    // ---------------- mappings ----------------

    private void mappingsRoute(WorkspaceService svc, HttpExchange ex, String[] parts)
            throws IOException {
        String method = ex.getRequestMethod();
        if (parts.length == 5) {
            if (!method.equals("GET")) {
                throw methodNotAllowed(method);
            }
            HttpSupport.writeJson(ex, 200,
                    Map.of("mappings", svc.workspace().mappings().values(),
                            "unconfirmed", mappings.unconfirmedDescriptions(svc)));
            return;
        }
        if (parts.length == 7 && parts[5].equals("confirm") && method.equals("POST")) {
            JsonNode n = HttpSupport.readJson(ex);
            String user = HttpSupport.required(n, "user");
            var confirmed = mappings.confirm(svc, parts[6], user);
            HttpSupport.writeJson(ex, 200, confirmed);
            return;
        }
        throw ValidationException.of("NOT_FOUND", "unknown mappings route");
    }

    // ---------------- comparisons ----------------

    private final Map<String, ComparisonResult> comparisonCache = new LinkedHashMap<>();

    private void comparisonsRoute(WorkspaceService svc, HttpExchange ex, String[] parts)
            throws IOException {
        if (!ex.getRequestMethod().equals("POST") || parts.length != 5) {
            throw methodNotAllowed(ex.getRequestMethod());
        }
        JsonNode n = HttpSupport.readJson(ex);
        List<String> sourceIds = new ArrayList<>();
        n.path("sourceIds").forEach(s -> sourceIds.add(s.asText()));
        double threshold = n.path("confidenceThreshold").asDouble(0.0);
        boolean quarantine = n.path("quarantineResampleEdges").asBoolean(true);
        ComparisonParams params = new ComparisonParams(threshold, sourceIds, quarantine);
        ComparisonResult result = comparisons.compare(svc, params);
        comparisonCache.put(result.comparisonId(), result);
        HttpSupport.writeJson(ex, 201, result);
    }

    // ---------------- decisions ----------------

    private void decisionsRoute(WorkspaceService svc, HttpExchange ex, String[] parts)
            throws IOException {
        String method = ex.getRequestMethod();
        if (parts.length == 5 && method.equals("GET")) {
            List<Map<String, Object>> decisions = new ArrayList<>();
            new ConsensusService().materialize(svc.workspace())
                    .forEach(d -> decisions.add(decisionToMap(d)));
            HttpSupport.writeJson(ex, 200, Map.of(
                    "eventVersion", svc.workspace().eventVersion(),
                    "decisions", decisions,
                    "events", svc.workspace().events()));
            return;
        }
        if (parts.length == 5 && method.equals("POST")) {
            submitDecision(svc, ex);
            return;
        }
        if (parts.length == 7 && parts[6].equals("undo") && method.equals("POST")) {
            JsonNode n = HttpSupport.readJson(ex);
            var event = consensus.undo(svc, parts[5], HttpSupport.required(n, "user"));
            HttpSupport.writeJson(ex, 201, Map.of("event", event,
                    "eventVersion", svc.workspace().eventVersion()));
            return;
        }
        if (parts.length == 7 && parts[6].equals("redo") && method.equals("POST")) {
            JsonNode n = HttpSupport.readJson(ex);
            var event = consensus.redo(svc, parts[5], HttpSupport.required(n, "user"));
            HttpSupport.writeJson(ex, 201, Map.of("event", event,
                    "eventVersion", svc.workspace().eventVersion()));
            return;
        }
        throw methodNotAllowed(method);
    }

    private void submitDecision(WorkspaceService svc, HttpExchange ex) throws IOException {
        JsonNode n = HttpSupport.readJson(ex);
        DecisionType type = DecisionType.valueOf(HttpSupport.required(n, "decisionType"));
        Polygon region = JsonCodecs.polygon(n.path("region"));
        String sourceId = textOrNull(n, "sourceId");
        String canonicalKey = textOrNull(n, "canonicalKey");
        long baseVersion = n.path("baseVersion").asLong(svc.workspace().eventVersion());
        String author = HttpSupport.required(n, "author");
        String note = textOrNull(n, "note");
        SubmitDecisionRequest req = new SubmitDecisionRequest(type, region, sourceId,
                canonicalKey, baseVersion, author, note);
        ConsensusService.SubmitResult result = consensus.submit(svc, req);
        if (result.hasConflict()) {
            HttpSupport.writeJson(ex, 409, Map.of(
                    "conflict", result.conflict(),
                    "autoMergedEvents", result.applied(),
                    "eventVersion", svc.workspace().eventVersion()));
        } else {
            HttpSupport.writeJson(ex, 201, Map.of(
                    "events", result.applied(),
                    "eventVersion", svc.workspace().eventVersion()));
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    // ---------------- releases ----------------

    private void gate(WorkspaceService svc, HttpExchange ex) throws IOException {
        var gate = releaseService.evaluate(svc);
        HttpSupport.writeJson(ex, 200,
                Map.of("allowed", gate.allowed, "problems", gate.problems));
    }

    private void releasesRoute(WorkspaceService svc, HttpExchange ex, String[] parts)
            throws IOException {
        if (!ex.getRequestMethod().equals("POST") || parts.length != 5) {
            throw methodNotAllowed(ex.getRequestMethod());
        }
        JsonNode n = HttpSupport.readJson(ex);
        ReleaseSnapshot release = releaseService.publish(svc, HttpSupport.required(n, "user"));
        HttpSupport.writeJson(ex, 201, release);
    }

    // ---------------- export ----------------

    private void export(WorkspaceService svc, HttpExchange ex) throws IOException {
        byte[] zip = exporter.exportBundle(svc, null);
        String filename = svc.workspace().workspaceId() + "-v"
                + svc.workspace().eventVersion() + ".gsb.zip";
        ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"" + filename + "\"");
        HttpSupport.writeBytes(ex, 200, "application/zip", zip);
    }

    // ---------------- tiles ----------------

    private void tilesRoute(WorkspaceService svc, HttpExchange ex, String[] parts)
            throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            throw methodNotAllowed(ex.getRequestMethod());
        }
        if (parts.length != 10) {
            throw ValidationException.of("BAD_TILE_PATH",
                    "expected tiles/{kind}/{key}/{z}/{x}/{y}.png");
        }
        String kind = parts[5];
        String key = parts[6].equals("-") ? null : parts[6];
        int tx = Integer.parseInt(parts[8]);
        int ty = Integer.parseInt(parts[9].replace(".png", ""));
        byte[] png = switch (kind) {
            case "source" -> tiles.sourceTile(svc, key, tx, ty);
            case "consensus" -> tiles.consensusTile(svc, tx, ty);
            case "comparison" -> {
                ComparisonResult cr = comparisonCache.get(key);
                if (cr == null) {
                    throw ValidationException.of("COMPARISON_MISSING",
                            "comparison id not known in this server session");
                }
                yield tiles.comparisonTile(svc, cr, tx, ty);
            }
            default -> throw ValidationException.of("BAD_TILE_KIND", kind);
        };
        HttpSupport.writeBytes(ex, 200, "image/png", png);
    }

    static Map<String, Object> summary(WorkspaceService svc) {
        var ws = svc.workspace();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", ws.workspaceId());
        out.put("name", ws.name());
        out.put("createdAt", ws.createdAt());
        out.put("image", ws.image());
        out.put("canonicalGeometry", ws.canonicalGeometry());
        out.put("ruleSetVersion", ws.ruleSetVersion());
        out.put("eventVersion", ws.eventVersion());
        out.put("sources", ws.sources().values());
        out.put("mappings", ws.mappings().values());
        out.put("releases", ws.releases());
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Decision d : new ConsensusService().materialize(ws)) {
            decisions.add(decisionToMap(d));
        }
        out.put("decisions", decisions);
        out.put("events", ws.events());
        out.put("recoveryNote", svc.recoveryNote());
        return out;
    }

    static Map<String, Object> decisionToMap(Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", d.eventId());
        m.put("decisionType", d.decisionType());
        m.put("region", d.region());
        m.put("sourceId", d.sourceId());
        m.put("canonicalKey", d.canonicalKey());
        m.put("author", d.author());
        m.put("createdAt", d.createdAt());
        m.put("active", d.active());
        m.put("stale", d.stale());
        return m;
    }
}
