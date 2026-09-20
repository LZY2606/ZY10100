package gsb.service;

import gsb.analysis.Analysis;
import gsb.geo.Contour;
import gsb.geo.Poly;
import gsb.geo.RasterOps;
import gsb.json.Json;
import gsb.store.ApiException;
import gsb.store.EventLog;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.util.Hash;
import gsb.util.Ids;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Region-level decisions on the consensus layer. Decisions reference sources
 * and correction events only; input masks are never modified. Concurrent
 * editors working from the same log version get 409 with the conflict polygon;
 * non-overlapping portions are auto-merged.
 */
public final class DecisionService {
    private DecisionService() {}

    public static final class Outcome {
        public EventLog.Event event;
        public boolean conflicted;
        public long conflictPixels;
        public long appliedPixels;
        public List<Poly> conflictPolygons = List.of();
        public List<Poly> appliedPolygons = List.of();
        public List<String> conflictDecisionIds = List.of();
        public long serverVersion;
    }

    public static Outcome record(Workspace ws, Map<String, Object> req, String actor) {
        Model m = ws.model();
        long baseVersion = Json.lng(req, "baseVersion", -1);
        long serverVersion = ws.log.nextSeq() - 1;
        if (baseVersion < 0) {
            throw ApiException.badRequest("BASE_VERSION_REQUIRED",
                    "every edit must declare the log version it was drawn against");
        }
        if (baseVersion > serverVersion) {
            throw ApiException.conflict("VERSION_AHEAD",
                    "baseVersion " + baseVersion + " is newer than server " + serverVersion);
        }

        String kind = Json.str(req, "kind");
        if (kind == null || !List.of("ACCEPT", "CORRECT", "PENDING").contains(kind)) {
            throw ApiException.badRequest("BAD_KIND", "kind must be ACCEPT|CORRECT|PENDING");
        }
        List<List<Double>> polygon = ProjectorPolygons.from(
                gsb.json.Json.list(req, "polygon"));
        if (polygon.size() < 3) {
            throw ApiException.badRequest("BAD_POLYGON", "polygon needs >= 3 points");
        }
        if (m.image == null) {
            throw ApiException.badRequest("NO_IMAGE", "create the base image first");
        }
        boolean[] cells = RasterOps.rasterize(rawPoints(polygon), m.image.width, m.image.height);
        long area = RasterOps.count(cells);
        if (area == 0) {
            throw ApiException.badRequest("EMPTY_REGION", "polygon covers no pixels inside image");
        }

        String acceptedSourceId = null;
        String basedOnVersionId = null;
        Integer correctionClass = null;
        String correctionVocabularyId = null;
        if ("ACCEPT".equals(kind)) {
            acceptedSourceId = Json.str(req, "acceptedSourceId");
            Model.Source src = m.sources.get(acceptedSourceId);
            if (src == null) {
                throw ApiException.badRequest("BAD_SOURCE",
                        "acceptedSourceId does not identify a registered source");
            }
            Model.SourceVersion active = src.versions.get(src.activeVersionId);
            basedOnVersionId = active != null ? active.id : null;
        } else if ("CORRECT".equals(kind)) {
            correctionClass = Json.integer(req, "correctionClass", 0);
            correctionVocabularyId = Json.str(req, "correctionVocabularyId");
            if (correctionClass <= 0) {
                throw ApiException.badRequest("BAD_CORRECTION",
                        "correctionClass (label id) is required");
            }
        }

        boolean[] overlap = new boolean[cells.length];
        List<String> conflictIds = new ArrayList<>();
        for (Model.Decision d : m.decisions.values()) {
            if (d.undoOf != null || d.undoneBy != null) {
                continue;
            }
            if (d.committedLogSeq > baseVersion) {
                boolean[] other = RasterOps.rasterize(rawPoints(d.polygon),
                        m.image.width, m.image.height);
                boolean[] shared = RasterOps.intersect(cells, other);
                long sharedCount = RasterOps.count(shared);
                if (sharedCount > 0) {
                    conflictIds.add(d.id);
                    overlap = RasterOps.union(overlap, shared);
                }
            }
        }

        boolean[] applied = RasterOps.subtract(cells, overlap);
        long appliedCount = RasterOps.count(applied);
        long conflictCount = RasterOps.count(overlap);

        Outcome outcome = new Outcome();
        outcome.serverVersion = serverVersion;
        outcome.conflictPixels = conflictCount;
        outcome.appliedPixels = appliedCount;
        outcome.conflictDecisionIds = conflictIds;
        outcome.conflictPolygons = contours(overlap, m.image.width, m.image.height);
        outcome.appliedPolygons = contours(applied, m.image.width, m.image.height);

        if (conflictCount > 0 && appliedCount == 0) {
            outcome.conflicted = true;
            throw ApiException.conflict("EDIT_CONFLICT",
                    "the entire region was changed by another user on a newer version"
                            + " (their decision: " + String.join(", ", conflictIds) + ")")
                    .detail("re-fetch the workspace, inspect the conflict polygon, and"
                            + " re-merge on the latest version");
        }

        String polygonHash = polygonHash(polygon, area);
        String decisionId = Ids.create("dec");
        Map<String, Object> payload = Json.obj(
                "decisionId", decisionId,
                "kind", kind,
                "regionId", orDefault(req.get("regionId"), "manual"),
                "polygon", pointsJson(polygon),
                "areaPixels", area,
                "polygonHash", polygonHash,
                "basedOnLogSeq", baseVersion,
                "acceptedSourceId", acceptedSourceId == null ? "" : acceptedSourceId,
                "basedOnVersionId", basedOnVersionId == null ? "" : basedOnVersionId,
                "correctionClass", correctionClass == null ? 0 : correctionClass,
                "correctionVocabularyId", correctionVocabularyId == null ? "" : correctionVocabularyId);
        payload.put("committedLogSeq", ws.log.nextSeq());
        EventLog.Event event = ws.append("decision.recorded", payload, actor);
        outcome.event = event;
        outcome.conflicted = conflictCount > 0;
        return outcome;
    }

    /** Undo emits a reverse event; the original event is retained, not rewritten. */
    public static EventLog.Event undo(Workspace ws, String decisionId, String actor) {
        Model m = ws.model();
        Model.Decision original = m.decisions.get(decisionId);
        if (original == null) {
            throw ApiException.notFound("unknown decision " + decisionId);
        }
        if (original.undoneBy != null) {
            throw ApiException.conflict("ALREADY_UNDONE",
                    decisionId + " was already undone by " + original.undoneBy);
        }
        String reverseId = Ids.create("dec");
        Map<String, Object> payload = Json.obj(
                "decisionId", reverseId,
                "kind", "UNDO_" + original.kind,
                "regionId", original.regionId,
                "polygon", pointsJson(original.polygon),
                "areaPixels", original.areaPixels,
                "polygonHash", original.polygonHash,
                "basedOnLogSeq", ws.log.nextSeq() - 1,
                "undoOf", original.id,
                "acceptedSourceId", original.acceptedSourceId == null ? "" : original.acceptedSourceId,
                "basedOnVersionId", original.basedOnVersionId == null ? "" : original.basedOnVersionId);
        return ws.append("decision.undone", payload, actor);
    }

    static List<Poly> contours(boolean[] cells, int width, int height) {
        if (RasterOps.count(cells) == 0) {
            return List.of();
        }
        int[] labels = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            labels[i] = cells[i] ? 1 : 0;
        }
        return Contour.ofComponent(1, labels, width, height);
    }

    static String polygonHash(List<List<Double>> polygon, long area) {
        StringBuilder sb = new StringBuilder();
        for (List<Double> pt : polygon) {
            sb.append(Math.rint(pt.get(0) * 1e6) / 1e6).append(',')
                    .append(Math.rint(pt.get(1) * 1e6) / 1e6).append(';');
        }
        sb.append(area);
        return Hash.sha256Hex(sb.toString());
    }

    static List<List<Double>> rawPoints(List<List<Double>> polygon) {
        return polygon;
    }

    static List<Object> pointsJson(List<List<Double>> polygon) {
        List<Object> out = new ArrayList<>();
        for (List<Double> pt : polygon) {
            out.add(List.of(pt.get(0), pt.get(1)));
        }
        return out;
    }

    private static Object orDefault(Object v, Object dflt) {
        return v == null || v.toString().isBlank() ? dflt : v;
    }
}
