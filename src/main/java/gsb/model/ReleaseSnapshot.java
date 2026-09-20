package gsb.model;

import java.time.Instant;
import java.util.List;

/** Immutable published consensus version. Only produced when gating checks all pass. */
public record ReleaseSnapshot(
        String releaseId,
        int versionNo,
        Instant createdAt,
        String createdBy,
        long eventVersion,
        List<String> sourceFingerprints,
        String consensusBlobSha256,
        long consensusBlobBytes,
        String exportBlobSha256,
        String ruleSetVersion,
        long regionAreaPixels,
        String boundaryHash) {
}
