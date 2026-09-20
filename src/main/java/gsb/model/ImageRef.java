package gsb.model;

/**
 * Reference of the underlying high-resolution image. Two masks may only be compared when
 * their image fingerprints are identical; matching width/height alone is never enough.
 */
public record ImageRef(String imageId, String fingerprintSha256) {
    public ImageRef {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("imageId is required");
        }
        if (fingerprintSha256 == null || fingerprintSha256.isBlank()) {
            throw new IllegalArgumentException("image fingerprint is required");
        }
        fingerprintSha256 = fingerprintSha256.toLowerCase();
    }
}
