package gsb.service;

import gsb.model.DecisionEvent;
import gsb.model.Source;

import java.util.List;
import java.util.Map;

/**
 * Self-contained, hash-pinned export bundle. {@code manifest} is JSON; mask blobs are embedded
 * under their SHA-256 keys. Re-import recomputes region areas and the boundary hash and refuses
 * the bundle if either drifts.
 */
public record ExportBundle(
        String formatVersion,
        String ruleSetVersion,
        String imageFingerprint,
        gsb.geom.Geometry canonicalGeometry,
        List<Source> sources,
        List<gsb.model.ClassMapping> mappings,
        List<DecisionEvent> decisionHistory,
        Map<String, byte[]> blobs,
        String consensusBoundaryHash,
        long consensusRegionPixels,
        String releasedBy,
        java.time.Instant exportedAt) {

    public static final String FORMAT_VERSION = "gsb-export-1";
}
