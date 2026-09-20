package gsb.web;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.store.Model;
import gsb.store.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Models -> JSON views. */
final class Views {
    private Views() {}

    static Map<String, Object> image(Workspace ws) {
        Model m = ws.model();
        if (m.image == null) {
            return Json.obj("imageId", ws.imageId, "created", false,
                    "version", ws.log.nextSeq() - 1);
        }
        Model.ImageInfo img = m.image;
        return Json.obj(
                "imageId", img.id,
                "created", true,
                "version", ws.log.nextSeq() - 1,
                "name", img.name,
                "width", img.width,
                "height", img.height,
                "orientation", img.orientation,
                "spacingX", img.spacingX,
                "spacingY", img.spacingY,
                "fingerprint", img.fingerprint,
                "confidenceThreshold", m.confidenceThreshold,
                "ruleVersion", gsb.analysis.Analysis.RULE_VERSION,
                "vocabularies", vocabularies(m),
                "sources", sources(m),
                "mappings", mappings(m),
                "decisions", decisions(m),
                "releases", releases(m));
    }

    static List<Object> vocabularies(Model m) {
        List<Object> out = new ArrayList<>();
        for (Model.Vocabulary v : m.vocabularies.values()) {
            List<Object> cats = new ArrayList<>();
            v.categories.forEach((id, c) -> cats.add(Json.obj(
                    "id", c.id, "name", c.name, "color", c.color)));
            out.add(Json.obj("vocabularyId", v.id, "name", v.name,
                    "version", v.version, "categories", cats));
        }
        return out;
    }

    static List<Object> sources(Model m) {
        List<Object> out = new ArrayList<>();
        for (Model.Source s : m.sources.values()) {
            List<Object> versions = new ArrayList<>();
            for (Model.SourceVersion v : s.versions.values()) {
                versions.add(Json.obj(
                        "versionId", v.id,
                        "versionLabel", v.versionLabel,
                        "active", v.id.equals(s.activeVersionId),
                        "vocabularyId", v.vocabularyId,
                        "vocabularyVersion", v.vocabularyVersion,
                        "nativeWidth", v.nativeWidth,
                        "nativeHeight", v.nativeHeight,
                        "orientation", v.orientation,
                        "spacingX", v.spacingX,
                        "spacingY", v.spacingY,
                        "rawFingerprint", v.rawFingerprint,
                        "rawFormat", v.rawFormat,
                        "rawSize", v.rawSize,
                        "resampled", v.resampled,
                        "boundaryPixels", v.boundaryPixels,
                        "outOfBoundsPixels", v.outOfBoundsPixels,
                        "derivedFingerprint", v.derivedFingerprint,
                        "derivedFromRuleVersion", v.derivedFromRuleVersion,
                        "createdAt", v.createdAt,
                        "retracted", v.retracted));
            }
            out.add(Json.obj("sourceId", s.id, "name", s.name, "kind", s.kind,
                    "activeVersionId", s.activeVersionId, "versions", versions));
        }
        return out;
    }

    static List<Object> mappings(Model m) {
        List<Object> out = new ArrayList<>();
        for (Model.MappingGroup g : m.mappingGroups.values()) {
            List<Object> refs = new ArrayList<>();
            for (Model.CategoryRef r : g.refs) {
                Model.Vocabulary vocab = m.vocabularies.get(r.vocabularyId() + "@" + r.vocabularyVersion());
                String catName = vocab != null && vocab.categories.containsKey(r.categoryId())
                        ? vocab.categories.get(r.categoryId()).name : "?";
                refs.add(Json.obj("vocabularyId", r.vocabularyId(),
                        "vocabularyVersion", r.vocabularyVersion(),
                        "categoryId", r.categoryId(),
                        "categoryName", catName));
            }
            out.add(Json.obj("groupId", g.id, "status", g.status,
                    "canonicalName", g.canonicalName, "refs", refs,
                    "proposedAt", g.proposedAt, "confirmedAt", g.confirmedAt));
        }
        return out;
    }

    static List<Object> decisions(Model m) {
        List<Object> out = new ArrayList<>();
        for (Model.Decision d : m.decisions.values()) {
            out.add(Json.obj(
                    "decisionId", d.id,
                    "kind", d.kind,
                    "regionId", d.regionId,
                    "polygon", d.polygon,
                    "areaPixels", d.areaPixels,
                    "polygonHash", d.polygonHash,
                    "acceptedSourceId", nz(d.acceptedSourceId),
                    "basedOnVersionId", nz(d.basedOnVersionId),
                    "createdAt", d.createdAt,
                    "actor", d.actor,
                    "basedOnLogSeq", d.basedOnLogSeq,
                    "undoOf", nz(d.undoOf),
                    "undoneBy", nz(d.undoneBy),
                    "stale", d.stale,
                    "staleReason", nz(d.staleReason)));
        }
        return out;
    }

    static List<Object> releases(Model m) {
        List<Object> out = new ArrayList<>();
        for (Model.ReleaseInfo r : m.releases.values()) {
            out.add(Json.obj("releaseId", r.id, "createdAt", r.createdAt,
                    "logSeq", r.logSeq, "manifestHash", r.manifestHash,
                    "consensusBoundaryPixels", r.consensusBoundaryPixels,
                    "outOfBoundsPixels", r.outOfBoundsPixels));
        }
        return out;
    }

    static Map<String, Object> analysisView(Analysis a) {
        List<Object> regions = new ArrayList<>();
        for (Analysis.Region r : a.regions) {
            List<Object> rings = new ArrayList<>();
            r.rings.forEach(ring -> rings.add(ring.toPoints()));
            regions.add(Json.obj("id", r.id,
                    "kind", r.kindName(),
                    "pixels", r.pixelCount,
                    "boundaryPixels", r.boundaryPixels,
                    "outOfBoundsPixels", r.outOfBoundsPixels,
                    "boundaryHash", r.boundaryHash,
                    "rings", rings,
                    "witnessSourceVersions", r.witnessSourceVersions,
                    "disagreementLabels", r.disagreementConcepts));
        }
        return Json.obj("ruleVersion", a.ruleVersion,
                "confidenceThreshold", a.confidenceThreshold,
                "sourceVersionIds", a.sourceVersionIds,
                "sourceFingerprints", a.sourceFingerprints,
                "width", a.width,
                "height", a.height,
                "summary", Json.obj(
                        "agreePixels", a.summary.agreePixels,
                        "disagreePixels", a.summary.disagreePixels,
                        "uncoveredPixels", a.summary.uncoveredPixels,
                        "boundaryPixels", a.summary.boundaryPixels,
                        "outOfBoundsPixels", a.summary.outOfBoundsPixels),
                "regions", regions);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
