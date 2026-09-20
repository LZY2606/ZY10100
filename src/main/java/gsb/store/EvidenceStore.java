package gsb.store;

import gsb.util.Hash;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

/**
 * Content-addressed, append-only storage for raw evidence (original image /
 * mask uploads). Once written a blob is never overwritten or deleted by the
 * application; writes use a temp file + atomic move.
 */
public final class EvidenceStore {
    private final Path root;

    public EvidenceStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Store bytes and verify the caller-declared fingerprint.
     *
     * @throws FingerprintMismatchException if the declared fingerprint does not
     *     match the received bytes — width/height equality never bypasses this.
     */
    public Stored put(byte[] data, String declaredSha256) {
        String actual = Hash.sha256Hex(data);
        if (declaredSha256 != null && !declaredSha256.equals(actual)) {
            throw new FingerprintMismatchException(declaredSha256, actual, data.length);
        }
        Path target = pathFor(actual);
        if (!Files.exists(target)) {
            try {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                        + ProcessHandle.current().pid() + "-" + Thread.currentThread().getId());
                Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFail) {
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        Files.deleteIfExists(tmp);
                        throw e;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot store evidence", e);
            }
        }
        return new Stored(actual, target, data.length);
    }

    public byte[] read(String sha256) {
        Path p = pathFor(sha256);
        if (!Files.exists(p)) {
            throw new MissingEvidenceException(sha256);
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read evidence " + sha256, e);
        }
    }

    public boolean exists(String sha256) {
        return Files.exists(pathFor(sha256));
    }

    public Path pathFor(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bad evidence fingerprint: " + sha256);
        }
        return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4))
                .resolve(sha256);
    }

    public record Stored(String sha256, Path path, int size) {}

    public static final class FingerprintMismatchException extends IllegalArgumentException {
        public final String declared;
        public final String actual;

        public FingerprintMismatchException(String declared, String actual, int size) {
            super("image fingerprint mismatch: declared " + shortHash(declared)
                    + " but received bytes hash to " + shortHash(actual)
                    + " (" + size + " bytes); comparison refused (matching width/height is not enough)");
            this.declared = declared;
            this.actual = actual;
        }
    }

    public static final class MissingEvidenceException extends IllegalStateException {
        public MissingEvidenceException(String sha256) {
            super("missing raw evidence " + sha256
                    + " — import references evidence not present in this store");
        }
    }

    private static String shortHash(String h) {
        return h == null ? "null" : h.substring(0, Math.min(12, h.length())) + "...";
    }
}
