package gsb.service;

import gsb.storage.WorkspaceStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application root: owns the on-disk store and all open workspaces. A single instance serves the
 * HTTP layer; each workspace service serialises its own mutations.
 */
public final class AppKernel {
    private final WorkspaceStore store;
    private final Clock clock;
    private final Map<String, WorkspaceService> open = new LinkedHashMap<>();

    public AppKernel(Path dataDir, Clock clock) {
        this.store = new WorkspaceStore(dataDir);
        this.clock = clock;
        for (String id : store.listWorkspaceIds()) {
            WorkspaceService svc = new WorkspaceService(store, clock);
            svc.open(id);
            open.put(id, svc);
        }
    }

    public WorkspaceStore store() {
        return store;
    }

    public Clock clock() {
        return clock;
    }

    public synchronized List<String> workspaceIds() {
        return List.copyOf(open.keySet());
    }

    public synchronized WorkspaceService get(String id) {
        WorkspaceService svc = open.get(id);
        if (svc == null && store.listWorkspaceIds().contains(id)) {
            svc = new WorkspaceService(store, clock);
            svc.open(id);
            open.put(id, svc);
        }
        if (svc == null) {
            throw ValidationException.of("WORKSPACE_MISSING", "no workspace " + id);
        }
        return svc;
    }

    public synchronized WorkspaceService create(String id, String name,
                                                gsb.model.ImageRef image,
                                                gsb.geom.Geometry canonical) {
        WorkspaceService svc = new WorkspaceService(store, clock);
        svc.create(id, name, image, canonical);
        open.put(id, svc);
        return svc;
    }

    /** Register a workspace that was already initialised (e.g. via re-import). */
    public synchronized void register(WorkspaceService svc) {
        open.put(svc.workspace().workspaceId(), svc);
    }
}
