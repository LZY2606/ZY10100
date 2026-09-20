package gsb.model;

import java.util.List;

/**
 * Parameters of a pairwise or multi-source comparison.
 *
 * @param confidenceThreshold labels below the source confidence are excluded before comparison
 * @param sourceIds          sources participating; exactly two for pairwise comparisons
 * @param quarantineResampleEdges when true resample-edge pixels are reported separately and
 *                            never counted as genuine disagreement
 */
public record ComparisonParams(double confidenceThreshold, List<String> sourceIds,
                               boolean quarantineResampleEdges) {
    public static final String RULE_VERSION = "comparison-rules-1";

    public ComparisonParams {
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidence threshold must be within [0,1]");
        }
        sourceIds = List.copyOf(sourceIds);
    }
}
