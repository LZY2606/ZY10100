package gsb.service;

import gsb.model.DecisionEvent;
import gsb.model.ImageRef;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.storage.WorkspaceStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central application service. Owns the in-memory workspace, serialises all mutating
 * operations, persists immutable evidence immediately and appends consensus events to the WAL.
 * All state transitions flow through this object so the optimistic-lock and fingerprint rules
 * live in exactly one place.
 */
public final class WorkspaceService {
    private final WorkspaceStore store;
    private final Clock clock;
    private Workspace ws;

    public WorkspaceService(WorkspaceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public synchronized void create(String id, String name, ImageRef image,
                                    gsb.geom.Geometry canonical) {
        if (store.loadSnapshot(id) != null) {
            throw ValidationException.of("WORKSPACE_EXISTS", "workspace " + id + " already exists");
        }
        this.ws = new Workspace(id, name, Instant.now(clock), image, canonical,
                new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>(), 0,
                new ArrayList<>(), Workspace.RULESET_VERSION);
        store.saveSnapshot(ws);
    }

    /** Open existing workspace: load snapshot then replay the authoritative event log. */
    public synchronized void open(String id) {
        Workspace snapshot = store.loadSnapshot(id);
        if (snapshot == null) {
            throw ValidationException.of("WORKSPACE_MISSING", "no workspace " + id);
        }
        gsb.storage.EventLog.Recovered recovery = store.eventLog(id).recover();
        List<DecisionEvent> events = recovery.events();
        this.ws = new Workspace(snapshot.workspaceId(), snapshot.name(), snapshot.createdAt(),
                snapshot.image(), snapshot.canonicalGeometry(),
                snapshot.sources(), snapshot.mappings(),
                events, events.size(), snapshot.releases(), snapshot.ruleSetVersion());
        this.recoveryNote = recovery.droppedLines() == 0 ? null
                : "recovered after crash: discarded " + recovery.droppedLines() + " torn event line(s)";
    }

    private String recoveryNote;

    public synchronized String recoveryNote() {
        return recoveryNote;
    }

    public synchronized Workspace workspace() {
        return ws;
    }

    public synchronized WorkspaceStore store() {
        return store;
    }

    public synchronized Clock clock() {
        return clock;
    }

    public synchronized String newId() {
        return UUID.randomUUID().toString();
    }

    public synchronized void persistSnapshot() {
        store.saveSnapshot(ws);
    }

    public synchronized void addSource(Source source) {
        Map<String, Source> sources = new LinkedHashMap<>(ws.sources());
        sources.put(source.sourceId(), source);
        this.ws = copyWith(sources, ws.mappings(), ws.events(), ws.eventVersion(), ws.releases());
        persistSnapshot();
    }

    public synchronized void putMapping(gsb.model.ClassMapping mapping) {
        Map<String, gsb.model.ClassMapping> mappings = new LinkedHashMap<>(ws.mappings());
        mappings.put(mapping.mappingId(), mapping);
        this.ws = copyWith(ws.sources(), mappings, ws.events(), ws.eventVersion(), ws.releases());
        persistSnapshot();
    }

    public synchronized void appendEvent(DecisionEvent event) {
        store.eventLog(ws.workspaceId()).append(event);
        List<DecisionEvent> events = new ArrayList<>(ws.events());
        events.add(event);
        this.ws = copyWith(ws.sources(), ws.mappings(), events, events.size(), ws.releases());
        // Snapshot is a convenience cache; the log is the source of truth.
        persistSnapshot();
    }

    public synchronized void addRelease(gsb.model.ReleaseSnapshot release) {
        List<gsb.model.ReleaseSnapshot> releases = new ArrayList<>(ws.releases());
        releases.add(release);
        this.ws = copyWith(ws.sources(), ws.mappings(), ws.events(), ws.eventVersion(), releases);
        persistSnapshot();
    }

    private Workspace copyWith(Map<String, Source> sources,
                               Map<String, gsb.model.ClassMapping> mappings,
                               List<DecisionEvent> events, long version,
                               List<gsb.model.ReleaseSnapshot> releases) {
        return new Workspace(ws.workspaceId(), ws.name(), ws.createdAt(), ws.image(),
                ws.canonicalGeometry(), sources, mappings, events, version, releases,
                ws.ruleSetVersion());
    }

    static String shortId() {
        return UUID.randomUUID().toString();
    }
}
