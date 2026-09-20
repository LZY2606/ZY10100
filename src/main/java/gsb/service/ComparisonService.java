package gsb.service;

import gsb.model.ClassMapping;
import gsb.model.ComparisonParams;
import gsb.model.ComparisonResult;
import gsb.model.ComparisonStats;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.rle.ConfidenceCodec;
import gsb.rle.RleCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes pairwise agreement on the canonical grid.
 *
 * Pixels are classified as AGREE / DISAGREE / UNCOVERED / RESAMPLE_EDGE / EXCLUDED_BY_CONFIDENCE.
 * Agreement is defined on the canonical class key via CONFIRMED mappings only; two same-named
 * classes whose mapping is merely proposed still disagree, which is what forces human review.
 */
public final class ComparisonService {

    public ComparisonResult compare(WorkspaceService svc, ComparisonParams params) {
        Workspace ws = svc.workspace();
        if (params.sourceIds().size() != 2) {
            throw ValidationException.of("COMPARE_ARITY",
                    "pairwise comparison requires exactly 2 source ids, got "
                            + params.sourceIds().size());
        }
        Source a = ws.source(params.sourceIds().get(0));
        Source b = ws.source(params.sourceIds().get(1));
        if (a == null || b == null) {
            throw ValidationException.of("SOURCE_MISSING",
                    "unknown source in comparison: " + params.sourceIds());
        }
        // Fingerprint gate (defence in depth: ingestion already enforces this).
        if (!a.image().fingerprintSha256().equals(b.image().fingerprintSha256())
                || !a.image().fingerprintSha256().equals(ws.image().fingerprintSha256())) {
            throw ValidationException.of("FINGERPRINT_CONFLICT",
                    "cannot compare masks bound to different image fingerprints");
        }

        int width = ws.canonicalGeometry().width();
        int height = ws.canonicalGeometry().height();
        int pixels = width * height;

        RleCodec.Grid ga = RleCodec.decode(svc.store().blobs().get(a.maskBlobSha256()));
        RleCodec.Grid gb = RleCodec.decode(svc.store().blobs().get(b.maskBlobSha256()));
        double[] ca = ConfidenceCodec.decode(svc.store().blobs().get(a.confidenceBlobSha256()));
        double[] cb = ConfidenceCodec.decode(svc.store().blobs().get(b.confidenceBlobSha256()));

        Map<Integer, String> canonA = confirmedCanonicalKeys(svc, a.sourceId());
        Map<Integer, String> canonB = confirmedCanonicalKeys(svc, b.sourceId());

        int[] cat = new int[pixels];
        long agree = 0, disagree = 0, uncovered = 0, edges = 0, excluded = 0;

        for (int i = 0; i < pixels; i++) {
            boolean edge = ga.edgeFlags()[i] != 0 || gb.edgeFlags()[i] != 0;
            boolean aOk = ca[i] >= params.confidenceThreshold();
            boolean bOk = cb[i] >= params.confidenceThreshold();
            int la = ga.labels()[i];
            int lb = gb.labels()[i];

            if (!aOk || !bOk) {
                cat[i] = ComparisonResult.CAT_EXCLUDED_CONF;
                excluded++;
                continue;
            }
            if (edge && params.quarantineResampleEdges()) {
                cat[i] = ComparisonResult.CAT_RESAMPLE_EDGE;
                edges++;
                continue;
            }
            if (la == 0 && lb == 0) {
                cat[i] = ComparisonResult.CAT_UNCOVERED;
                uncovered++;
                continue;
            }
            if (la == 0 || lb == 0) {
                cat[i] = ComparisonResult.CAT_DISAGREE;
                disagree++;
                continue;
            }
            String ka = canonA.get(la);
            String kb = canonB.get(lb);
            if (ka == null || kb == null || !ka.equals(kb)) {
                cat[i] = ComparisonResult.CAT_DISAGREE;
                disagree++;
            } else {
                cat[i] = ComparisonResult.CAT_AGREE;
                agree++;
            }
        }

        byte[] blob = RleCodec.encode(width, height, cat, new byte[pixels]);
        String hash = svc.store().blobs().put(blob);
        ComparisonStats stats = new ComparisonStats(pixels, agree, disagree, uncovered,
                edges, excluded);
        return new ComparisonResult(svc.newId(), Instant.now(svc.clock()), params,
                ComparisonParams.RULE_VERSION,
                List.of(a.maskBlobSha256(), b.maskBlobSha256()),
                stats, hash, blob.length);
    }

    private Map<Integer, String> confirmedCanonicalKeys(WorkspaceService svc, String sourceId) {
        Map<Integer, String> map = new HashMap<>();
        for (ClassMapping m : svc.workspace().mappings().values()) {
            if (m.sourceId().equals(sourceId) && m.confirmed()) {
                map.put(m.classId(), m.canonicalKey());
            }
        }
        return map;
    }

    /** Convenience for UI: per-pixel raster of a stored comparison blob. */
    public RleCodec.Grid raster(WorkspaceService svc, ComparisonResult result) {
        return RleCodec.decode(svc.store().blobs().get(result.classRasterBlobSha256()));
    }
}
