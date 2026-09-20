package gsb.model;

import java.time.Instant;

/**
 * Derived artefact of comparing sources. Derived data always carries the rule version and the
 * fingerprints of every source it was computed from, so it can be invalidated automatically.
 */
public record ComparisonResult(
        String comparisonId,
        Instant createdAt,
        ComparisonParams params,
        String ruleVersion,
        java.util.List<String> sourceFingerprints,
        ComparisonStats stats,
        String classRasterBlobSha256,
        long blobBytes) {

    /** Per-pixel category codes stored in the class raster. */
    public static final int CAT_BACKGROUND = 0;
    public static final int CAT_AGREE = 1;
    public static final int CAT_DISAGREE = 2;
    public static final int CAT_UNCOVERED = 3;
    public static final int CAT_RESAMPLE_EDGE = 4;
    public static final int CAT_EXCLUDED_CONF = 5;
}
