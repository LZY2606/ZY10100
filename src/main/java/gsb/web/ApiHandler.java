package gsb.web;

import com.sun.net.httpserver.HttpExchange;
import gsb.json.Json;
import gsb.service.AnalysisService;
import gsb.service.DecisionService;
import gsb.service.ExportService;
import gsb.service.ImportService;
import gsb.service.IngestService;
import gsb.service.MappingService;
import gsb.service.ReleaseService;
import gsb.service.TileService;
import gsb.store.ApiException;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.util.Ids;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes /api/* to the application services. */
public final class ApiHandler {
    private final WorkspaceStore store;

    public ApiHandler(WorkspaceStore store) {
        this.store = store;
    }

    public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (ApiException e) {
            Map<String, Object> body = Json.obj(
                    "error", true,
                    "status", e.status,
                    "code", e.code,
                    "message", e.getMessage(),
                    "details", e.details);
            Http.writeJson(ex, e.status, body);
        } catch (IllegalArgumentException e) {
            Http.writeJson(ex, 400, Json.obj("error", true, "status", 400,
                    "code", "BAD_REQUEST", "message", e.getMessage(), "details", List.of()));
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            System.err.println("[api] unhandled: " + sw);
            Http.writeJson(ex, 500, Json.obj("error", true, "status", 500,
                    "code", "INTERNAL_ERROR",
                    "message", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "details", List.of()));
        }
    }

    private void route(HttpExchange ex) throws IOException {
        URI uri = ex.getRequestURI();
        String path = uri.getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/images") && method.equals("GET")) {
            listImages(ex);
            return;
        }
        if (path.equals("/api/images") && method.equals("POST")) {
            createImageStart(ex);
            return;
        }
        if (path.matches("/api/images/[^/]+$") && method.equals("GET")) {
            String id = segment(path, 3);
            Http.writeJson(ex, 200, Views.image(store.open(id)));
            return;
        }
        if (path.matches("/api/images/[^/]+/upload$") && method.equals("POST")) {
            uploadImage(ex, segment(path, 3));
            return;
        }
        if (path.matches("/api/images/[^/]+/vocabularies$") && method.equals("POST")) {
            IngestService.importVocabulary(store.open(segment(path, 3)),
                    Http.jsonBody(ex), Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(store.open(segment(path, 3))));
            return;
        }
        if (path.matches("/api/images/[^/]+/sources$") && method.equals("POST")) {
            IngestService.registerSource(store.open(segment(path, 3)),
                    Http.jsonBody(ex), Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(store.open(segment(path, 3))));
            return;
        }
        if (path.matches("/api/images/[^/]+/sources/[^/]+/versions$")
                && method.equals("POST")) {
            uploadVersion(ex, segment(path, 3));
            return;
        }
        if (path.matches("/api/images/[^/]+/threshold$") && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            double t = Json.dbl(Http.jsonBody(ex), "threshold", 0);
            AnalysisService.setThreshold(ws, t, Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(ws));
            return;
        }
        if (path.matches("/api/images/[^/]+/analysis$") && method.equals("GET")) {
            Workspace ws = store.open(segment(path, 3));
            Http.writeJson(ex, 200, Views.analysisView(AnalysisService.compute(ws)));
            return;
        }
        if (path.matches("/api/images/[^/]+/mappings/[^/]+/confirm$")
                && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            MappingService.confirm(ws, segment(path, 5), Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(ws));
            return;
        }
        if (path.matches("/api/images/[^/]+/mappings/[^/]+/reject$")
                && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            MappingService.reject(ws, segment(path, 5), Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(ws));
            return;
        }
        if (path.matches("/api/images/[^/]+/decisions$") && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            DecisionService.Outcome out = DecisionService.record(ws,
                    Http.jsonBody(ex), Http.actor(ex));
            Map<String, Object> response = Json.obj(
                    "image", Views.image(ws),
                    "appliedPixels", out.appliedPixels,
                    "conflictPixels", out.conflictPixels,
                    "conflicted", out.conflicted,
                    "conflictDecisionIds", out.conflictDecisionIds,
                    "conflictPolygons", polygons(out.conflictPolygons),
                    "appliedPolygons", polygons(out.appliedPolygons));
            Http.writeJson(ex, out.conflicted ? 200 : 200, response);
            return;
        }
        if (path.matches("/api/images/[^/]+/decisions/[^/]+/undo$")
                && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            DecisionService.undo(ws, segment(path, 5), Http.actor(ex));
            Http.writeJson(ex, 200, Views.image(ws));
            return;
        }
        if (path.matches("/api/images/[^/]+/release$") && method.equals("POST")) {
            Workspace ws = store.open(segment(path, 3));
            Map<String, Object> r = ReleaseService.publish(ws, Http.actor(ex));
            Http.writeJson(ex, 200, Json.obj("release", r, "image", Views.image(ws)));
            return;
        }
        if (path.matches("/api/images/[^/]+/export$") && method.equals("GET")) {
            Workspace ws = store.open(segment(path, 3));
            byte[] zip = ExportService.exportZip(ws);
            Http.writeBytes(ex, 200, "application/zip", zip);
            return;
        }
        if (path.equals("/api/import") && method.equals("POST")) {
            importBundle(ex);
            return;
        }
        if (path.matches("/api/images/[^/]+/tiles/[^/]+/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.png$")
                && method.equals("GET")) {
            tile(ex, path);
            return;
        }
        throw new ApiException(404, "NO_ROUTE", "no route for " + method + " " + path);
    }

    private void createImageStart(HttpExchange ex) throws IOException {
        Map<String, Object> req = Http.jsonBody(ex);
        String imageId = req.get("imageId") == null || req.get("imageId").toString().isBlank()
                ? Ids.create("img") : req.get("imageId").toString();
        if (store.registry().containsKey(imageId)) {
            throw ApiException.conflict("IMAGE_EXISTS", "workspace " + imageId + " exists");
        }
        Workspace ws = store.create(imageId);
        Http.writeJson(ex, 200, Json.obj("imageId", imageId,
                "uploadUrl", "/api/images/" + imageId + "/upload"));
    }

    private void uploadImage(HttpExchange ex, String imageId) throws IOException {
        Multipart mp = Multipart.parse(Http.body(ex),
                ex.getRequestHeaders().getFirst("Content-Type"));
        Map<String, Object> meta = Json.parseObject(mp.field("meta"));
        Multipart.Part file = mp.file("file");
        if (file == null) {
            throw ApiException.badRequest("NO_FILE", "multipart field 'file' is required");
        }
        Workspace ws = store.open(imageId);
        IngestService.createImage(ws, meta, file.data, Http.actor(ex));
        Http.writeJson(ex, 200, Views.image(ws));
    }

    private void uploadVersion(HttpExchange ex, String imageId) throws IOException {
        Multipart mp = Multipart.parse(Http.body(ex),
                ex.getRequestHeaders().getFirst("Content-Type"));
        Map<String, Object> meta = Json.parseObject(mp.field("meta"));
        Multipart.Part file = mp.file("file");
        if (file == null) {
            throw ApiException.badRequest("NO_FILE", "multipart field 'file' is required");
        }
        Workspace ws = store.open(imageId);
        gsb.store.Model.SourceVersion v = IngestService.importSourceVersion(ws, meta,
                file.data, Http.actor(ex));
        MappingService.proposeForActiveLabels(ws);
        Http.writeJson(ex, 200, Json.obj("version", Json.obj(
                "versionId", v.id,
                "resampled", v.resampled,
                "boundaryPixels", v.boundaryPixels,
                "outOfBoundsPixels", v.outOfBoundsPixels,
                "derivedFingerprint", v.derivedFingerprint),
                "image", Views.image(ws)));
    }

    private void listImages(HttpExchange ex) throws IOException {
        List<Object> out = new java.util.ArrayList<>();
        store.registry().keySet().forEach(id -> {
            Workspace ws = store.open(id);
            out.add(Json.obj("imageId", id,
                    "name", ws.model().image != null ? ws.model().image.name : id,
                    "version", ws.log.nextSeq() - 1,
                    "created", ws.model().image != null));
        });
        Http.writeJson(ex, 200, Json.obj("images", out));
    }

    private void tile(HttpExchange ex, String path) throws IOException {
        String[] parts = path.split("/");
        String imageId = parts[3];
        String layer = java.net.URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
        int z = Integer.parseInt(parts[6]);
        int x = Integer.parseInt(parts[7]);
        int y = Integer.parseInt(parts[8].replace(".png", ""));
        Workspace ws = store.open(imageId);
        var analysis = AnalysisService.compute(ws);
        byte[] png = TileService.renderTile(ws, analysis, layer, z, x, y);
        Http.writeBytes(ex, 200, "image/png", png);
    }

    private void importBundle(HttpExchange ex) throws IOException {
        Multipart mp = Multipart.parse(Http.body(ex),
                ex.getRequestHeaders().getFirst("Content-Type"));
        Multipart.Part file = mp.file("file");
        if (file == null) {
            throw ApiException.badRequest("NO_FILE", "multipart field 'file' is required");
        }
        String imageId = mp.field("imageId");
        Map<String, Object> result = ImportService.importBundle(store, file.data,
                imageId, Http.actor(ex));
        Http.writeJson(ex, 200, result);
    }

    private static List<Object> polygons(List<gsb.geo.Poly> polys) {
        List<Object> out = new java.util.ArrayList<>();
        for (gsb.geo.Poly p : polys) {
            out.add(p.toPoints());
        }
        return out;
    }

    private static String segment(String path, int index) {
        return path.split("/")[index];
    }
}
