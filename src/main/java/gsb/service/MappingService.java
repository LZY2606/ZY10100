package gsb.service;

import gsb.json.Json;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.util.Ids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Category table reconciliation. Categories with the same display name but
 * different vocabulary versions are NEVER auto-merged: the system proposes a
 * mapping group in PROPOSED state and publication requires confirmation.
 */
public final class MappingService {
    private MappingService() {}

    /** Ensure a PROPOSED/CONFIRMED group covers the given refs (by name). */
    public static List<String> proposeForActiveLabels(Workspace ws) {
        Model m = ws.model();
        List<String> touched = new ArrayList<>();
        Map<String, List<Model.CategoryRef>> byName = new LinkedHashMap<>();
        for (Model.SourceVersion v : m.activeVersions()) {
            Model.Vocabulary vocab = m.vocabularies.get(v.vocabularyId + "@" + v.vocabularyVersion);
            if (vocab == null) {
                continue;
            }
            for (int label = 1; label <= v.maxLabel; label++) {
                Model.CategoryDef cat = vocab.categories.get(Integer.toString(label));
                if (cat == null) {
                    continue;
                }
                byName.computeIfAbsent(normalize(cat.name), k -> new ArrayList<>())
                        .add(new Model.CategoryRef(v.vocabularyId, v.vocabularyVersion,
                                Integer.toString(label)));
            }
        }
        for (Map.Entry<String, List<Model.CategoryRef>> entry : byName.entrySet()) {
            List<Model.CategoryRef> refs = dedupe(entry.getValue());
            if (refs.size() < 2) {
                continue;
            }
            String proposalKey = proposalKey(refs);
            boolean exists = m.mappingGroups.values().stream()
                    .anyMatch(g -> proposalKey(refs(g)).equals(proposalKey));
            if (exists) {
                continue;
            }
            String groupId = Ids.create("map");
            List<Object> refJson = new ArrayList<>();
            for (Model.CategoryRef ref : refs) {
                refJson.add(Json.obj("vocabularyId", ref.vocabularyId(),
                        "vocabularyVersion", ref.vocabularyVersion(),
                        "categoryId", ref.categoryId()));
            }
            ws.append("mapping.proposed", Json.obj(
                    "groupId", groupId,
                    "canonicalName", entry.getKey(),
                    "refs", refJson), "system");
            touched.add(groupId);
        }
        return touched;
    }

    public static void confirm(Workspace ws, String groupId, String actor) {
        Model.MappingGroup g = ws.model().mappingGroups.get(groupId);
        if (g == null) {
            throw ApiException.notFound("unknown mapping group " + groupId);
        }
        if (g.refs.size() < 2) {
            throw ApiException.unprocessable("MAPPING_TOO_SMALL",
                    "mapping groups need at least two category references");
        }
        ws.append("mapping.confirmed", Json.obj("groupId", groupId), actor);
    }

    public static void reject(Workspace ws, String groupId, String actor) {
        Model.MappingGroup g = ws.model().mappingGroups.get(groupId);
        if (g == null) {
            throw ApiException.notFound("unknown mapping group " + groupId);
        }
        ws.append("mapping.rejected", Json.obj("groupId", groupId), actor);
    }

    static List<Model.CategoryRef> refs(Model.MappingGroup g) {
        return g.refs;
    }

    static String proposalKey(List<Model.CategoryRef> refs) {
        List<String> keys = new ArrayList<>();
        for (Model.CategoryRef r : refs) {
            keys.add(r.key());
        }
        keys.sort(String::compareTo);
        return String.join("|", keys);
    }

    private static List<Model.CategoryRef> dedupe(List<Model.CategoryRef> in) {
        Map<String, Model.CategoryRef> unique = new LinkedHashMap<>();
        for (Model.CategoryRef r : in) {
            unique.putIfAbsent(r.key(), r);
        }
        return new ArrayList<>(unique.values());
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase().replaceAll("\\s+", "_");
    }
}
