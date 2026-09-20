package gsb.model;

import java.time.Instant;

/**
 * One immutable entry in the append-only consensus event log.
 *
 * @param seq            global monotonically increasing sequence number
 * @param eventId        stable id
 * @param type           CREATE / REVISE / UNDO / REDO
 * @param decisionType   ACCEPT_SOURCE / CORRECTION / PENDING
 * @param region         polygon in canonical pixel coordinates
 * @param sourceId       referenced source revision (ACCEPT_SOURCE); may be stale later
 * @param canonicalKey   canonical class key for CORRECTION
 * @param baseVersion    number of events the editor had applied when submitting (optimistic lock)
 * @param author         user id
 * @param createdAt      timestamp
 * @param undoesEventId  for UNDO: the event being reversed
 * @param redoOfEventId  for REDO: the undone event being replayed
 * @param note           optional human note
 */
public record DecisionEvent(
        long seq,
        String eventId,
        EventType type,
        DecisionType decisionType,
        Polygon region,
        String sourceId,
        String canonicalKey,
        long baseVersion,
        String author,
        Instant createdAt,
        String undoesEventId,
        String redoOfEventId,
        String note) {
}
