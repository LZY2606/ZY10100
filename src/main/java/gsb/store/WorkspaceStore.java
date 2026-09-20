package gsb.store;

import gsb.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Root data directory holding all image workspaces plus a small registry. */
public final class WorkspaceStore {
    private final Path root;
    private final Path imagesDir;
    private final Path registryFile;

    public WorkspaceStore(Path root) {
        this.root = root;
        this.imagesDir = root.resolve("images");
        this.registryFile = root.resolve("registry.json");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() {
        return root;
    }

    public Workspace create(String imageId) {
        Path dir = imagesDir.resolve(imageId);
        try {
            Files.createDirectories(dir.resolve("evidence"));
            Files.createDirectories(dir.resolve("cache"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Workspace ws = new Workspace(imageId, dir);
        register(imageId);
        return ws;
    }

    public Workspace open(String imageId) {
        Path dir = imagesDir.resolve(imageId);
        if (!Files.isDirectory(dir)) {
            throw ApiException.notFound("no workspace for image " + imageId);
        }
        Workspace ws = new Workspace(imageId, dir);
        ws.model();
        return ws;
    }

    public Map<String, Map<String, Object>> registry() {
        if (!Files.exists(registryFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String text = Files.readString(registryFile, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) Json.parse(text);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) v;
                out.put(k, entry);
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void register(String imageId) {
        Map<String, Map<String, Object>> reg = registry();
        reg.putIfAbsent(imageId, Json.obj("imageId", imageId,
                "createdAt", java.time.Instant.now().toString()));
        try {
            Files.writeString(registryFile, Json.pretty(reg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
