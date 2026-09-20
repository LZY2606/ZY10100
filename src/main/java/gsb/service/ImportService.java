package gsb.service;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.util.Ids;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Re-imports an export bundle into a fresh workspace and verifies geometry. */
public final class ImportService {
    private ImportService() {}

    public static Map<String, Object> importBundle(WorkspaceStore store, byte[] zipBytes,
                                                   String newImageId, String actor) {
        Map<String, Object> verification = ExportService.verifyZip(zipBytes);
        if (!(boolean) verification.get("ok")) {
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) verification.get("errors");
            throw ApiException.unprocessable("IMPORT_VERIFICATION_FAILED",
                    "export bundle failed verification; import refused")
                    .detail(errors.toArray(new String[0]));
        }
        Map<String, byte[]> entries = ExportService.unzip(zipBytes);
        String imageId = newImageId == null || newImageId.isBlank()
                ? Ids.create("img") : newImageId;
        if (store.registry().containsKey(imageId)) {
            throw ApiException.conflict("IMAGE_EXISTS",
                    "workspace " + imageId + " already exists; choose a new id");
        }
        Workspace ws = store.create(imageId);

        @SuppressWarnings("unchecked")
        Map<String, Object> imageJson = (Map<String, Object>) Json.parse(
                new String(entries.get("image.json"), StandardCharsets.UTF_8));
        byte[] imagePng = entries.get("image.png");
        IngestService.createImage(ws, Json.obj(
                "name", imageJson.get("name"),
                "width", imageJson.get("width"),
                "height", imageJson.get("height"),
                "orientation", imageJson.get("orientation"),
                "spacingX", imageJson.get("spacingX"),
                "spacingY", imageJson.get("spacingY"),
                "fingerprint", imageJson.get("fingerprint")), imagePng, actor);

        @SuppressWarnings("unchecked")
        List<Object> vocabs = (List<Object>) Json.parse(
                new String(entries.get("vocabularies.json"), StandardCharsets.UTF_8));
        for (Object vo : vocabs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) vo;
            IngestService.importVocabulary(ws, v, "import");
        }

        @SuppressWarnings("unchecked")
        List<Object> sources = (List<Object>) Json.parse(
                new String(entries.get("sources.json"), StandardCharsets.UTF_8));
        for (Object so : sources) {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) so;
            IngestService.registerSource(ws, Json.obj(
                    "sourceId", src.get("sourceId"),
                    "name", src.get("name"),
                    "kind", src.get("kind")), "import");
            for (Object vo2 : Json.list(src, "versions")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) vo2;
                byte[] raw = entries.get(Json.str(v, "rawFile"));
                Model.SourceVersion created = IngestService.importSourceVersion(ws, Json.obj(
                        "sourceId", src.get("sourceId"),
                        "versionLabel", v.get("versionLabel"),
                        "vocabularyId", v.get("vocabularyId"),
                        "vocabularyVersion", v.get("vocabularyVersion"),
                        "nativeWidth", v.get("nativeWidth"),
                        "nativeHeight", v.get("nativeHeight"),
                        "orientation", v.get("orientation"),
                        "spacingX", v.get("spacingX"),
                        "spacingY", v.get("spacingY"),
                        "maskFormat", v.get("rawFormat"),
                        "rawFingerprint", v.get("rawFingerprint")), raw, "import");
                if (!created.id.equals(v.get("versionId"))) {
                    // ids are generated; compare derived content instead
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> mappings = (List<Object>) Json.parse(
                new String(entries.get("mappings.json"), StandardCharsets.UTF_8));
        for (Object mo : mappings) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) mo;
            List<Object> refs = new ArrayList<>(Json.list(g, "refs"));
            ws.append("mapping.proposed", Json.obj(
                    "groupId", g.get("groupId"),
                    "canonicalName", g.get("canonicalName"),
                    "refs", refs), "import");
            if ("CONFIRMED".equals(Json.str(g, "status"))) {
                MappingService.confirm(ws, Json.str(g, "groupId"), "import");
            } else if ("REJECTED".equals(Json.str(g, "status"))) {
                MappingService.reject(ws, Json.str(g, "groupId"), "import");
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> decisions = (List<Object>) Json.parse(
                new String(entries.get("decisions.json"), StandardCharsets.UTF_8));
        for (Object d0 : decisions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) d0;
            if (orEmpty(Json.str(d, "kind")).startsWith("UNDO_")) {
                continue;
            }
            long base = Json.lng(d, "basedOnLogSeq", 0);
            Map<String, Object> payload = Json.obj(
                    "baseVersion", Math.min(base, ws.log.nextSeq() - 1),
                    "kind", Json.str(d, "kind"),
                    "regionId", d.get("regionId"),
                    "polygon", d.get("polygon"),
                    "acceptedSourceId", d.get("acceptedSourceId"),
                    "correctionClass", d.get("correctionClass"),
                    "correctionVocabularyId", d.get("correctionVocabularyId"));
            DecisionService.record(ws, payload, orEmpty(Json.str(d, "actor"), "import"));
        }
        for (Object d0 : decisions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) d0;
            if (!orEmpty(Json.str(d, "kind")).startsWith("UNDO_")) {
                continue;
            }
            String undoOfNew = findDecisionByHash(ws, Json.str(d, "polygonHash"));
            if (undoOfNew != null) {
                DecisionService.undo(ws, undoOfNew, "import");
            }
        }

        Analysis replayed = AnalysisService.compute(ws);
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(
                new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        List<String> mismatches = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object> regionDigest = (List<Object>) manifest.get("regions");
        if (regionDigest != null && regionDigest.size() != replayed.regions.size()) {
            mismatches.add("region count " + replayed.regions.size()
                    + " != exported " + regionDigest.size());
        }
        for (int i = 0; regionDigest != null && i < Math.min(regionDigest.size(),
                replayed.regions.size()); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exported = (Map<String, Object>) regionDigest.get(i);
            Analysis.Region actual = replayed.regions.get(i);
            if (Json.lng(exported, "pixels", -1) != actual.pixelCount) {
                mismatches.add("region " + i + " area " + actual.pixelCount
                        + " != exported " + exported.get("pixels"));
            }
            if (!Json.str(exported, "boundaryHash").equals(actual.boundaryHash)) {
                mismatches.add("region " + i + " boundary hash differs from export");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) manifest.get("summary");
        if (summary != null) {
            checkSum(summary, "agreePixels", replayed.summary.agreePixels, mismatches);
            checkSum(summary, "disagreePixels", replayed.summary.disagreePixels, mismatches);
            checkSum(summary, "uncoveredPixels", replayed.summary.uncoveredPixels, mismatches);
            checkSum(summary, "boundaryPixels", replayed.summary.boundaryPixels, mismatches);
            checkSum(summary, "outOfBoundsPixels", replayed.summary.outOfBoundsPixels, mismatches);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageId", imageId);
        result.put("ok", mismatches.isEmpty());
        result.put("mismatches", mismatches);
        result.put("logSeq", ws.log.nextSeq() - 1);
        return result;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    private static void checkSum(Map<String, Object> summary, String key, long actual,
                                 List<String> mismatches) {
        if (Json.lng(summary, key, -1) != actual) {
            mismatches.add(key + " replayed=" + actual + " exported=" + summary.get(key));
        }
    }

    private static String findDecisionByHash(Workspace ws, String hash) {
        for (Model.Decision d : ws.model().decisions.values()) {
            if (hash.equals(d.polygonHash) && d.undoneBy == null && d.undoOf == null) {
                return d.id;
            }
        }
        return null;
    }
}
