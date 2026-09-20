package gsb.service;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.util.Hash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Self-contained release export: compressed masks, source index and decision
 * history. Re-import verifies fingerprints and recomputes region areas and
 * boundary hashes so they stay identical.
 */
public final class ExportService {
    public static final String EXPORT_FORMAT = "pairwise-gsb-export-v1";

    private ExportService() {}

    public static byte[] exportZip(Workspace ws) {
        Model m = ws.model();
        if (m.image == null) {
            throw ApiException.badRequest("NO_IMAGE", "nothing to export");
        }
        Analysis analysis = AnalysisService.compute(ws);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                java.util.Set<String> written = new java.util.HashSet<>();
                Map<String, Object> imageJson = Json.obj(
                        "imageId", m.image.id,
                        "name", m.image.name,
                        "width", m.image.width,
                        "height", m.image.height,
                        "orientation", m.image.orientation,
                        "spacingX", m.image.spacingX,
                        "spacingY", m.image.spacingY,
                        "fingerprint", m.image.fingerprint,
                        "format", m.image.rawFormat,
                        "createdAt", m.image.createdAt);
                putText(zip, "image.json", Json.pretty(imageJson));
                putBytes(zip, "image.png", ws.evidence.read(m.image.rawEvidence));

                List<Object> vocabJson = new ArrayList<>();
                for (Model.Vocabulary v : m.vocabularies.values()) {
                    List<Object> cats = new ArrayList<>();
                    v.categories.forEach((id, c) -> cats.add(Json.obj(
                            "id", c.id, "name", c.name, "color", c.color)));
                    vocabJson.add(Json.obj("vocabularyId", v.id, "name", v.name,
                            "version", v.version, "categories", cats));
                }
                putText(zip, "vocabularies.json", Json.pretty(vocabJson));

                List<Object> sourceIndex = new ArrayList<>();
                for (Model.Source s : m.sources.values()) {
                    List<Object> versions = new ArrayList<>();
                    for (Model.SourceVersion v : s.versions.values()) {
                        Map<String, Object> vj = Json.obj(
                                "versionId", v.id,
                                "versionLabel", v.versionLabel,
                                "vocabularyId", v.vocabularyId,
                                "vocabularyVersion", v.vocabularyVersion,
                                "nativeWidth", v.nativeWidth,
                                "nativeHeight", v.nativeHeight,
                                "orientation", v.orientation,
                                "spacingX", v.spacingX,
                                "spacingY", v.spacingY,
                                "rawFile", "evidence/" + v.rawEvidence,
                                "rawFormat", v.rawFormat,
                                "rawFingerprint", v.rawFingerprint,
                                "resampled", v.resampled,
                                "boundaryPixels", v.boundaryPixels,
                                "outOfBoundsPixels", v.outOfBoundsPixels,
                                "derivedFile", "evidence/" + v.derivedEvidence,
                                "derivedFingerprint", v.derivedFingerprint,
                                "derivedFromRuleVersion", v.derivedFromRuleVersion,
                                "maxLabel", v.maxLabel,
                                "createdAt", v.createdAt);
                        versions.add(vj);
                        putOnce(zip, written, "evidence/" + v.rawEvidence,
                                ws.evidence.read(v.rawEvidence));
                        putOnce(zip, written, "evidence/" + v.derivedEvidence,
                                ws.evidence.read(v.derivedEvidence));
                    }
                    sourceIndex.add(Json.obj(
                            "sourceId", s.id,
                            "name", s.name,
                            "kind", s.kind,
                            "activeVersionId", s.activeVersionId,
                            "versions", versions));
                }
                putText(zip, "sources.json", Json.pretty(sourceIndex));

                List<Object> mappingJson = new ArrayList<>();
                m.mappingGroups.forEach((id, g) -> {
                    List<Object> refs = new ArrayList<>();
                    g.refs.forEach(r -> refs.add(Json.obj("vocabularyId", r.vocabularyId(),
                            "vocabularyVersion", r.vocabularyVersion(),
                            "categoryId", r.categoryId())));
                    mappingJson.add(Json.obj("groupId", g.id, "status", g.status,
                            "canonicalName", g.canonicalName, "refs", refs));
                });
                putText(zip, "mappings.json", Json.pretty(mappingJson));

                List<Object> decisionHistory = new ArrayList<>();
                m.decisions.forEach((id, d) -> decisionHistory.add(Json.obj(
                        "decisionId", d.id,
                        "kind", d.kind,
                        "regionId", d.regionId,
                        "polygon", d.polygon,
                        "areaPixels", d.areaPixels,
                        "polygonHash", d.polygonHash,
                        "acceptedSourceId", nz(d.acceptedSourceId),
                        "basedOnVersionId", nz(d.basedOnVersionId),
                        "correctionClass", d.correctionClass == null ? 0 : d.correctionClass,
                        "correctionVocabularyId", nz(d.correctionVocabularyId),
                        "createdAt", d.createdAt,
                        "actor", d.actor,
                        "basedOnLogSeq", d.basedOnLogSeq,
                        "undoOf", nz(d.undoOf),
                        "undoneBy", nz(d.undoneBy),
                        "stale", d.stale,
                        "staleReason", nz(d.staleReason))));
                putText(zip, "decisions.json", Json.pretty(decisionHistory));

                List<Object> regionDigest = ReleaseService.regionsDigest(analysis);
                Map<String, Object> manifest = Json.obj(
                        "format", EXPORT_FORMAT,
                        "ruleVersion", Analysis.RULE_VERSION,
                        "confidenceThreshold", m.confidenceThreshold,
                        "imageFingerprint", m.image.fingerprint,
                        "sourceVersionIds", analysis.sourceVersionIds,
                        "sourceFingerprints", analysis.sourceFingerprints,
                        "summary", Json.obj(
                                "agreePixels", analysis.summary.agreePixels,
                                "disagreePixels", analysis.summary.disagreePixels,
                                "uncoveredPixels", analysis.summary.uncoveredPixels,
                                "boundaryPixels", analysis.summary.boundaryPixels,
                                "outOfBoundsPixels", analysis.summary.outOfBoundsPixels),
                        "regions", regionDigest);
                String manifestText = Json.pretty(manifest);
                putText(zip, "manifest.json", manifestText);
                putText(zip, "manifest.sha256", Hash.sha256Hex(
                        manifestText.getBytes(StandardCharsets.UTF_8)));
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("export failed", e);
        }
    }

    /**
     * Verify an export bundle against the recorded fingerprints and recomputed
     * region areas/boundary hashes. Returns a verification report.
     */
    public static Map<String, Object> verifyZip(byte[] zipBytes) {
        Map<String, byte[]> entries = unzip(zipBytes);
        List<String> errors = new ArrayList<>();
        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) {
            throw ApiException.badRequest("BAD_EXPORT", "manifest.json missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(
                new String(manifestBytes, StandardCharsets.UTF_8));
        String recordedHash = new String(entries.getOrDefault("manifest.sha256", new byte[0]),
                StandardCharsets.UTF_8).trim();
        if (!Hash.sha256Hex(manifestBytes).equals(recordedHash)) {
            errors.add("manifest hash mismatch");
        }
        byte[] imageBytes = entries.get("image.png");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageJson = (Map<String, Object>) Json.parse(
                new String(entries.get("image.json"), StandardCharsets.UTF_8));
        String imageFp = Json.str(imageJson, "fingerprint");
        if (imageBytes == null || !Hash.sha256Hex(imageBytes).equals(imageFp)) {
            errors.add("image evidence fingerprint mismatch");
        }
        @SuppressWarnings("unchecked")
        List<Object> sources = (List<Object>) Json.parse(
                new String(entries.get("sources.json"), StandardCharsets.UTF_8));
        for (Object so : sources) {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) so;
            for (Object vo : Json.list(src, "versions")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) vo;
                checkEntry(entries, Json.str(v, "rawFile"), Json.str(v, "rawFingerprint"), errors);
                checkEntry(entries, Json.str(v, "derivedFile"),
                        Json.str(v, "derivedFingerprint"), errors);
                byte[] derived = entries.get(Json.str(v, "derivedFile"));
                if (derived != null) {
                    try {
                        Mask mask = MrleCodec.decode(derived);
                        long oob = mask.countLabel(-1);
                        long boundary = mask.countBoundary();
                        if (oob != Json.lng(v, "outOfBoundsPixels", -2)) {
                            errors.add("derived OOB count mismatch for " + Json.str(v, "versionId"));
                        }
                        if (boundary != Json.lng(v, "boundaryPixels", -2)) {
                            errors.add("derived boundary count mismatch for "
                                    + Json.str(v, "versionId"));
                        }
                    } catch (RuntimeException e) {
                        errors.add("cannot decode derived mask " + Json.str(v, "versionId")
                                + ": " + e.getMessage());
                    }
                }
            }
        }

        boolean ok = errors.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", ok);
        report.put("format", manifest.get("format"));
        report.put("ruleVersion", manifest.get("ruleVersion"));
        report.put("imageFingerprint", imageFp);
        report.put("errors", errors);
        @SuppressWarnings("unchecked")
        List<Object> regions = (List<Object>) manifest.get("regions");
        report.put("regionCount", regions == null ? 0 : regions.size());
        return report;
    }

    private static void checkEntry(Map<String, byte[]> entries, String name, String fp,
                                   List<String> errors) {
        byte[] data = entries.get(name);
        if (data == null) {
            errors.add("export missing entry " + name);
        } else if (!Hash.sha256Hex(data).equals(fp)) {
            errors.add("fingerprint mismatch for " + name);
        }
    }

    static Map<String, byte[]> unzip(byte[] zipBytes) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                zin.transferTo(b);
                out.put(e.getName(), b.toByteArray());
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("BAD_EXPORT", "cannot read zip: " + ex.getMessage());
        }
        return out;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        putBytes(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putOnce(ZipOutputStream zip, java.util.Set<String> written,
                                String name, byte[] data) throws IOException {
        if (written.add(name)) {
            putBytes(zip, name, data);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
