package gsb.model;

/** Current materialised state of a region after folding the event log. */
public record Decision(
        String eventId,
        DecisionType decisionType,
        Polygon region,
        String sourceId,
        String canonicalKey,
        String author,
        java.time.Instant createdAt,
        boolean active,
        boolean stale) {

    public Decision withStale(boolean value) {
        return new Decision(eventId, decisionType, region, sourceId, canonicalKey,
                author, createdAt, active, value);
    }
}
