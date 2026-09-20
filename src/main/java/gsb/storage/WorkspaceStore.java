package gsb.storage;

import gsb.model.Workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * On-disk repository of workspaces. Layout under {@code <dataDir>}:
 * <pre>
 *   blobs/&lt;shard&gt;/&lt;sha&gt;.bin      content-addressed immutable evidence
 *   workspaces/&lt;id&gt;/workspace.json   atomic metadata snapshot
 *   workspaces/&lt;id&gt;/events.log       append-only decision log (replayed on open)
 * </pre>
 */
public final class WorkspaceStore {
    private final Path root;
    private final BlobStore blobs;

    public WorkspaceStore(Path dataDir) {
        this.root = dataDir;
        this.blobs = new BlobStore(dataDir.resolve("blobs"));
        try {
            Files.createDirectories(root.resolve("workspaces"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialise data dir " + dataDir, e);
        }
    }

    public BlobStore blobs() {
        return blobs;
    }

    public Path workspaceDir(String id) {
        return root.resolve("workspaces").resolve(id);
    }

    public EventLog eventLog(String id) {
        return new EventLog(workspaceDir(id).resolve("events.log"));
    }

    public void saveSnapshot(Workspace ws) {
        Path dir = workspaceDir(ws.workspaceId());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create workspace dir", e);
        }
        Path target = dir.resolve("workspace.json");
        Path tmp = dir.resolve(".workspace.json.tmp");
        try {
            Files.write(tmp, Json.write(ws));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("workspace snapshot write failed", e);
        }
    }

    public Workspace loadSnapshot(String id) {
        Path target = workspaceDir(id).resolve("workspace.json");
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return Json.read(Files.readAllBytes(target), Workspace.class);
        } catch (Exception e) {
            throw new IllegalStateException("workspace snapshot unreadable for " + id
                    + " (events.log remains authoritative): " + e.getMessage(), e);
        }
    }

    public List<String> listWorkspaceIds() {
        Path dir = root.resolve("workspaces");
        try (Stream<Path> entries = Files.list(dir)) {
            List<String> ids = new ArrayList<>();
            entries.filter(Files::isDirectory).forEach(p -> {
                if (Files.exists(p.resolve("workspace.json"))) {
                    ids.add(p.getFileName().toString());
                }
            });
            ids.sort(String::compareTo);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("workspace listing failed", e);
        }
    }
}
