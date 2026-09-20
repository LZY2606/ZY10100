package gsb.service;

import gsb.model.DecisionType;
import gsb.model.Polygon;

/**
 * User region decision submission.
 *
 * @param baseVersion event count the browser had loaded (optimistic concurrency)
 */
public record SubmitDecisionRequest(
        DecisionType decisionType,
        Polygon region,
        String sourceId,
        String canonicalKey,
        long baseVersion,
        String author,
        String note) {
}
