package gsb.store;

import gsb.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Appends only: rebuilds {@link Model} from the verified event stream. */
public final class Projector {
    private Projector() {}

    public static Model apply(Model m, EventLog.Event e) {
        Map<String, Object> p = e.payload == null ? Map.of() : e.payload;
        switch (e.type) {
            case "image.created" -> applyImageCreated(m, p, e);
            case "threshold.changed" -> m.confidenceThreshold = Json.dbl(p, "threshold", 0);
            case "vocabulary.imported" -> applyVocabulary(m, p, e);
            case "source.registered" -> applySourceRegistered(m, p);
            case "source.version.imported" -> applySourceVersion(m, p, e);
            case "source.activated" -> {
                Model.Source s = m.sources.get(Json.str(p, "sourceId"));
                if (s != null) {
                    s.activeVersionId = Json.str(p, "versionId");
                }
            }
            case "mapping.proposed" -> applyMappingProposed(m, p, e);
            case "mapping.confirmed", "mapping.rejected" -> {
                Model.MappingGroup g = m.mappingGroups.get(Json.str(p, "groupId"));
                if (g != null) {
                    g.status = e.type.endsWith("confirmed") ? "CONFIRMED" : "REJECTED";
                    g.confirmedAt = e.at;
                    g.actor = e.actor;
                }
            }
            case "decision.recorded" -> applyDecision(m, p, e);
            case "decision.undone" -> applyUndo(m, p, e);
            case "release.published" -> applyRelease(m, p, e);
            default -> {
                // Unknown events are ignored by older code but remain in the log.
            }
        }
        return m;
    }

    public static Model project(List<EventLog.Event> events) {
        Model m = new Model();
        for (EventLog.Event e : events) {
            apply(m, e);
        }
        markStale(m);
        return m;
    }

    private static void applyImageCreated(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.ImageInfo img = new Model.ImageInfo();
        img.id = Json.str(p, "imageId");
        img.name = Json.str(p, "name");
        img.width = Json.integer(p, "width", 0);
        img.height = Json.integer(p, "height", 0);
        img.orientation = Json.integer(p, "orientation", 0);
        img.spacingX = Json.dbl(p, "spacingX", 1);
        img.spacingY = Json.dbl(p, "spacingY", 1);
        img.fingerprint = Json.str(p, "fingerprint");
        img.rawEvidence = Json.str(p, "rawEvidence");
        img.rawFormat = Json.str(p, "rawFormat");
        img.createdAt = e.at;
        m.image = img;
    }

    private static void applyVocabulary(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.Vocabulary v = new Model.Vocabulary();
        v.id = Json.str(p, "vocabularyId");
        v.name = Json.str(p, "name");
        v.version = Json.str(p, "version");
        v.createdAt = e.at;
        for (Object raw : Json.list(p, "categories")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cm = (Map<String, Object>) raw;
            Model.CategoryDef c = new Model.CategoryDef();
            c.id = Json.str(cm, "id");
            c.name = Json.str(cm, "name");
            c.color = Json.str(cm, "color");
            v.categories.put(c.id, c);
        }
        // Same id but different version is a distinct vocabulary row; names alone
        // never merge categories across versions.
        m.vocabularies.put(v.id + "@" + v.version, v);
    }

    private static void applySourceRegistered(Model m, Map<String, Object> p) {
        Model.Source s = new Model.Source();
        s.id = Json.str(p, "sourceId");
        s.name = Json.str(p, "name");
        s.kind = Json.str(p, "kind");
        s.activeVersionId = null;
        m.sources.put(s.id, s);
    }

    private static void applySourceVersion(Model m, Map<String, Object> p, EventLog.Event e) {
        String sourceId = Json.str(p, "sourceId");
        Model.Source s = m.sources.get(sourceId);
        if (s == null) {
            s = new Model.Source();
            s.id = sourceId;
            s.name = sourceId;
            s.kind = "unknown";
            m.sources.put(sourceId, s);
        }
        Model.SourceVersion v = new Model.SourceVersion();
        v.id = Json.str(p, "versionId");
        v.sourceId = sourceId;
        v.versionLabel = Json.str(p, "versionLabel");
        v.actor = e.actor;
        v.createdAt = e.at;
        v.vocabularyId = Json.str(p, "vocabularyId");
        v.vocabularyVersion = Json.str(p, "vocabularyVersion");
        v.nativeWidth = Json.integer(p, "nativeWidth", 0);
        v.nativeHeight = Json.integer(p, "nativeHeight", 0);
        v.orientation = Json.integer(p, "orientation", 0);
        v.spacingX = Json.dbl(p, "spacingX", 1);
        v.spacingY = Json.dbl(p, "spacingY", 1);
        v.rawEvidence = Json.str(p, "rawEvidence");
        v.rawFormat = Json.str(p, "rawFormat");
        v.rawFingerprint = Json.str(p, "rawFingerprint");
        v.rawSize = Json.lng(p, "rawSize", 0);
        v.resampled = Json.bool(p, "resampled", false);
        v.boundaryPixels = Json.lng(p, "boundaryPixels", 0);
        v.outOfBoundsPixels = Json.lng(p, "outOfBoundsPixels", 0);
        v.derivedEvidence = Json.str(p, "derivedEvidence");
        v.derivedFingerprint = Json.str(p, "derivedFingerprint");
        v.derivedFromRuleVersion = Json.str(p, "derivedFromRuleVersion");
        v.maxLabel = Json.integer(p, "maxLabel", 0);
        s.versions.put(v.id, v);
        if (s.activeVersionId == null) {
            s.activeVersionId = v.id;
        }
    }

    private static void applyMappingProposed(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.MappingGroup g = new Model.MappingGroup();
        g.id = Json.str(p, "groupId");
        g.status = "PROPOSED";
        g.canonicalName = Json.str(p, "canonicalName");
        g.proposedAt = e.at;
        for (Object raw : Json.list(p, "refs")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rm = (Map<String, Object>) raw;
            g.refs.add(new Model.CategoryRef(Json.str(rm, "vocabularyId"),
                    Json.str(rm, "vocabularyVersion"), Json.str(rm, "categoryId")));
        }
        m.mappingGroups.put(g.id, g);
    }

    private static void applyDecision(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.Decision d = new Model.Decision();
        d.id = Json.str(p, "decisionId");
        d.kind = Json.str(p, "kind");
        d.regionId = Json.str(p, "regionId");
        d.acceptedSourceId = Json.str(p, "acceptedSourceId");
        d.basedOnVersionId = Json.str(p, "basedOnVersionId");
        d.polygonHash = Json.str(p, "polygonHash");
        d.areaPixels = Json.lng(p, "areaPixels", 0);
        d.polygon = polygonOf(Json.list(p, "polygon"));
        d.correctionClass = p.containsKey("correctionClass")
                ? Json.integer(p, "correctionClass", 0) : null;
        d.correctionVocabularyId = Json.str(p, "correctionVocabularyId");
        d.createdAt = e.at;
        d.actor = e.actor;
        d.basedOnLogSeq = Json.lng(p, "basedOnLogSeq", 0);
        d.committedLogSeq = Json.lng(p, "committedLogSeq", d.basedOnLogSeq);
        m.decisions.put(d.id, d);
    }

    private static void applyUndo(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.Decision d = new Model.Decision();
        d.id = Json.str(p, "decisionId");
        d.kind = Json.str(p, "kind");
        d.regionId = Json.str(p, "regionId");
        d.acceptedSourceId = Json.str(p, "acceptedSourceId");
        d.basedOnVersionId = Json.str(p, "basedOnVersionId");
        d.polygonHash = Json.str(p, "polygonHash");
        d.areaPixels = Json.lng(p, "areaPixels", 0);
        d.polygon = polygonOf(Json.list(p, "polygon"));
        d.createdAt = e.at;
        d.actor = e.actor;
        d.basedOnLogSeq = Json.lng(p, "basedOnLogSeq", 0);
        d.undoOf = Json.str(p, "undoOf");
        m.decisions.put(d.id, d);
        Model.Decision original = m.decisions.get(d.undoOf);
        if (original != null) {
            original.undoneBy = d.id;
        }
    }

    private static void applyRelease(Model m, Map<String, Object> p, EventLog.Event e) {
        Model.ReleaseInfo r = new Model.ReleaseInfo();
        r.id = Json.str(p, "releaseId");
        r.createdAt = e.at;
        r.actor = e.actor;
        r.logSeq = Json.lng(p, "logSeq", e.seq);
        r.consensusBoundaryPixels = Json.lng(p, "consensusBoundaryPixels", 0);
        r.outOfBoundsPixels = Json.lng(p, "outOfBoundsPixels", 0);
        r.manifestHash = Json.str(p, "manifestHash");
        m.releases.put(r.id, r);
    }

    /** Decisions recorded against a source version that is no longer active are stale. */
    static void markStale(Model m) {
        for (Model.Decision d : m.decisions.values()) {
            d.stale = false;
            d.staleReason = null;
            if (d.basedOnVersionId == null) {
                continue;
            }
            Model.Source s = m.sourceOfVersion(d.basedOnVersionId);
            if (s == null || !d.basedOnVersionId.equals(s.activeVersionId)) {
                d.stale = true;
                d.staleReason = "source version is no longer active (a newer algorithm result exists)";
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<List<Double>> polygonOf(List<Object> raw) {
        List<List<Double>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            List<Object> pt = (List<Object>) o;
            out.add(List.of(((Number) pt.get(0)).doubleValue(), ((Number) pt.get(1)).doubleValue()));
        }
        return out;
    }
}
