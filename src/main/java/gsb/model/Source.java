package gsb.model;

import gsb.geom.Geometry;

import java.time.Instant;

/**
 * Immutable evidence record of one mask ingestion. Once accepted this record (and its blobs)
 * is never rewritten in place. Algorithm updates arrive as a NEW source revision
 * ({@code revisionOf} / {@code revisionNo}), and decisions referencing older revisions are
 * preserved but flagged stale.
 */
public record Source(
        String sourceId,
        String algorithm,
        int revisionNo,
        String revisionOf,
        Instant receivedAt,
        ImageRef image,
        Geometry geometry,
        Vocabulary vocabulary,
        String maskBlobSha256,
        String confidenceBlobSha256,
        long maskBytes,
        double meanConfidence) {

    public boolean fingerprintMatches(ImageRef other) {
        return image.fingerprintSha256().equals(other.fingerprintSha256());
    }
}
