package gsb.model;

/** Pixel counts for one comparison. All counts are on the canonical (first source) grid. */
public record ComparisonStats(
        long totalPixels,
        long agreePixels,
        long disagreePixels,
        long uncoveredPixels,
        long resampleEdgePixels,
        long excludedByConfidencePixels) {

    public long classifiedPixels() {
        return agreePixels + disagreePixels;
    }
}
