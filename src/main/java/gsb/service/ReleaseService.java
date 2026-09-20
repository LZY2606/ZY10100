package gsb.service;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.util.Hash;
import gsb.util.Ids;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Publication gate: confirmed mappings only and zero out-of-bounds pixels. */
public final class ReleaseService {
    private ReleaseService() {}

    public static Map<String, Object> publish(Workspace ws, String actor) {
        Model m = ws.model();
        if (m.image == null) {
            throw ApiException.badRequest("NO_IMAGE", "create the base image first");
        }
        List<String> blockers = new ArrayList<>();

        Analysis analysis = AnalysisService.compute(ws);
        if (analysis.summary.outOfBoundsPixels > 0) {
            blockers.add("out-of-bounds pixels present: " + analysis.summary.outOfBoundsPixels
                    + " pixels from resampled sources fall outside the image frame");
        }

        Set<Model.CategoryRef> neededRefs = new HashSet<>();
        for (Model.SourceVersion v : m.activeVersions()) {
            Model.Vocabulary vocab = m.vocabularies.get(v.vocabularyId + "@" + v.vocabularyVersion);
            if (vocab == null) {
                blockers.add("source version " + v.id + " references missing vocabulary "
                        + v.vocabularyId + "@" + v.vocabularyVersion);
                continue;
            }
            MaskUsageProbe probe = new MaskUsageProbe(ws, v);
            for (int label = 1; label <= v.maxLabel; label++) {
                if (probe.used(label)) {
                    neededRefs.add(new Model.CategoryRef(v.vocabularyId,
                            v.vocabularyVersion, Integer.toString(label)));
                }
            }
        }

        Set<Model.CategoryRef> confirmed = new HashSet<>();
        for (Model.MappingGroup g : m.mappingGroups.values()) {
            if ("CONFIRMED".equals(g.status)) {
                confirmed.addAll(g.refs);
            }
        }
        for (Model.CategoryRef ref : neededRefs) {
            if (!confirmed.contains(ref)) {
                Model.Vocabulary vocab = m.vocabularies.get(ref.vocabularyId() + "@" + ref.vocabularyVersion());
                String name = vocab != null && vocab.categories.containsKey(ref.categoryId())
                        ? vocab.categories.get(ref.categoryId()).name : "?";
                blockers.add("category mapping not confirmed: " + ref.key()
                        + " ('" + name + "') — same names across vocabulary versions"
                        + " are proposals until a user confirms them");
            }
        }

        if (!blockers.isEmpty()) {
            ApiException ex = ApiException.unprocessable("RELEASE_BLOCKED",
                    "release refused: " + blockers.size() + " blocker(s) remain");
            blockers.forEach(ex::detail);
            throw ex;
        }

        String releaseId = Ids.create("rel");
        long seq = ws.log.nextSeq();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("releaseId", releaseId);
        manifest.put("imageFingerprint", m.image.fingerprint);
        manifest.put("ruleVersion", Analysis.RULE_VERSION);
        manifest.put("summary", Json.obj(
                "agreePixels", analysis.summary.agreePixels,
                "disagreePixels", analysis.summary.disagreePixels,
                "uncoveredPixels", analysis.summary.uncoveredPixels,
                "boundaryPixels", analysis.summary.boundaryPixels,
                "outOfBoundsPixels", analysis.summary.outOfBoundsPixels));
        manifest.put("sources", analysis.sourceVersionIds);
        manifest.put("sourceFingerprints", analysis.sourceFingerprints);
        manifest.put("regions", regionsDigest(analysis));
        String manifestHash = Hash.sha256Hex(Json.write(manifest));

        ws.append("release.published", Json.obj(
                "releaseId", releaseId,
                "logSeq", seq,
                "manifestHash", manifestHash,
                "consensusBoundaryPixels", analysis.summary.boundaryPixels,
                "outOfBoundsPixels", analysis.summary.outOfBoundsPixels), actor);
        return Json.obj("releaseId", releaseId, "manifestHash", manifestHash,
                "logSeq", ws.log.nextSeq() - 1);
    }

    static List<Object> regionsDigest(Analysis analysis) {
        List<Object> out = new ArrayList<>();
        for (Analysis.Region r : analysis.regions) {
            out.add(Json.obj("id", r.id, "kind", r.kindName(),
                    "pixels", r.pixelCount,
                    "boundaryPixels", r.boundaryPixels,
                    "boundaryHash", r.boundaryHash));
        }
        return out;
    }

    private static final class MaskUsageProbe {
        private final gsb.mask.Mask mask;

        MaskUsageProbe(Workspace ws, Model.SourceVersion v) {
            this.mask = ws.alignedMask(v);
        }

        boolean used(int label) {
            return mask.countLabel(label) > 0;
        }
    }
}
