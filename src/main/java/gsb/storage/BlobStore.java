package gsb.storage;

import gsb.crypto.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Content-addressed, append-only blob storage. Blobs are never mutated: a new mask revision is
 * a new blob addressed by its own SHA-256. The first two hash bytes shard files to keep
 * directories small.
 */
public final class BlobStore {
    private final Path root;

    public BlobStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create blob store at " + root, e);
        }
    }

    /** Store bytes; returns the SHA-256 hex content id. Identical content de-duplicates. */
    public String put(byte[] data) {
        String hash = Hashes.sha256Hex(data);
        Path target = pathOf(hash);
        if (!Files.exists(target)) {
            Path tmp = target.resolveSibling(".tmp-" + hash + "-" + Thread.currentThread().threadId());
            try {
                Files.createDirectories(target.getParent());
                Files.write(tmp, data);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFail) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("blob write failed for " + hash, e);
            }
        }
        return hash;
    }

    public byte[] get(String sha256) {
        Path p = pathOf(sha256);
        if (!Files.exists(p)) {
            throw new IllegalStateException("missing blob " + sha256 + " at " + p);
        }
        try {
            byte[] data = Files.readAllBytes(p);
            String actual = Hashes.sha256Hex(data);
            if (!actual.equals(sha256.toLowerCase())) {
                throw new IllegalStateException("blob corrupt: expected " + sha256 + " got " + actual);
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("blob read failed for " + sha256, e);
        }
    }

    public boolean exists(String sha256) {
        return Files.exists(pathOf(sha256));
    }

    public long size(String sha256) {
        try {
            return Files.size(pathOf(sha256));
        } catch (IOException e) {
            throw new UncheckedIOException("blob size failed for " + sha256, e);
        }
    }

    private Path pathOf(String sha256) {
        String h = sha256.toLowerCase();
        if (h.length() != 64 || !h.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid blob sha256: " + sha256);
        }
        String shard = h.substring(0, 2);
        return root.resolve(shard).resolve(h + ".bin");
    }
}
