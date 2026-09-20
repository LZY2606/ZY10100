package gsb.model;

import java.time.Instant;

/**
 * Links a source-local class to a workspace-wide canonical class key.
 *
 * <p>Classes that share a display name but come from different {@code vocabVersion}s are
 * auto-proposed onto the same canonical key but are persisted with {@code confirmed=false}.
 * They are never silently merged: releasing a version requires every mapping to be confirmed.
 */
public record ClassMapping(
        String mappingId,
        String sourceId,
        int classId,
        String className,
        String vocabVersion,
        String canonicalKey,
        boolean confirmed,
        String confirmedBy,
        Instant confirmedAt) {

    public ClassMapping withConfirmation(String user, Instant at) {
        return new ClassMapping(mappingId, sourceId, classId, className, vocabVersion,
                canonicalKey, true, user, at);
    }
}
