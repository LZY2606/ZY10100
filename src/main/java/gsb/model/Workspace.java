package gsb.model;

import gsb.geom.Geometry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level workspace bound to one image fingerprint and one canonical pixel grid.
 * The canonical grid is taken from the first accepted source; every later source must either
 * match it exactly or be resampled onto it (never the reverse).
 */
public record Workspace(
        String workspaceId,
        String name,
        Instant createdAt,
        ImageRef image,
        Geometry canonicalGeometry,
        Map<String, Source> sources,
        Map<String, ClassMapping> mappings,
        List<DecisionEvent> events,
        long eventVersion,
        List<ReleaseSnapshot> releases,
        String ruleSetVersion) {

    public static final String RULESET_VERSION = "gsb-ruleset-1";

    public Workspace {
        sources = sources == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sources);
        mappings = mappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mappings);
        events = events == null ? new ArrayList<>() : new ArrayList<>(events);
        releases = releases == null ? new ArrayList<>() : new ArrayList<>(releases);
    }

    public Source source(String id) {
        return sources.get(id);
    }

    public List<Source> revisionsOf(String sourceId) {
        Source s = sources.get(sourceId);
        if (s == null) {
            return List.of();
        }
        List<Source> chain = new ArrayList<>();
        Source cur = s;
        chain.add(cur);
        while (cur.revisionOf() != null) {
            Source prev = sources.get(cur.revisionOf());
            if (prev == null) {
                break;
            }
            chain.add(prev);
            cur = prev;
        }
        return chain;
    }

    /** Latest revision that replaced the given source id (walking revisionOf forward is done by callers). */
    public Source latestRevisionOfFamily(String algorithm) {
        Source latest = null;
        for (Source s : sources.values()) {
            if (s.algorithm().equals(algorithm)) {
                if (latest == null || s.revisionNo() > latest.revisionNo()) {
                    latest = s;
                }
            }
        }
        return latest;
    }

    public boolean hasSource(String id) {
        return sources.containsKey(id);
    }
}
