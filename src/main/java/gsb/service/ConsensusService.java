package gsb.service;

import gsb.geom.Geometry;
import gsb.geom.Polygons;
import gsb.model.Decision;
import gsb.model.DecisionEvent;
import gsb.model.DecisionType;
import gsb.model.EventType;
import gsb.model.Polygon;
import gsb.model.Source;
import gsb.model.Workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Region-level decisions over the append-only consensus log.
 *
 * Concurrency: every submit carries the {@code baseVersion} the editor saw. If newer events
 * exist, overlapping pixels produce a {@link ConflictReport} (the later editor sees the exact
 * conflict polygons and can re-merge); non-overlapping parts are merged automatically.
 */
public final class ConsensusService {

    /** Fold the event log into current active decisions, flagging references to stale sources. */
    public List<Decision> materialize(Workspace ws) {
        Map<String, DecisionEvent> effective = new LinkedHashMap<>();
        for (DecisionEvent e : ws.events()) {
            switch (e.type()) {
                case CREATE, REVISE, REDO -> effective.put(decisionKey(e), e);
                case UNDO -> effective.remove(decisionKey(e));
            }
        }
        List<Decision> out = new ArrayList<>();
        for (DecisionEvent e : effective.values()) {
            boolean stale = e.sourceId() != null && isStale(ws, ws.source(e.sourceId()));
            out.add(new Decision(e.eventId(), e.decisionType(), e.region(), e.sourceId(),
                    e.canonicalKey(), e.author(), e.createdAt(), true, stale));
        }
        return out;
    }

    /** A source revision is stale when the same algorithm has a newer accepted revision. */
    public boolean isStale(Workspace ws, Source source) {
        if (source == null) {
            return false;
        }
        Source latest = ws.latestRevisionOfFamily(source.algorithm());
        return latest != null && latest.revisionNo() > source.revisionNo();
    }

    private String decisionKey(DecisionEvent e) {
        return switch (e.type()) {
            case UNDO -> e.undoesEventId();
            case REDO -> e.redoOfEventId() != null ? e.redoOfEventId() : e.eventId();
            default -> e.eventId();
        };
    }

    public SubmitResult submit(WorkspaceService svc, SubmitDecisionRequest req) {
        Workspace ws = svc.workspace();
        validate(svc, req);

        Geometry g = ws.canonicalGeometry();
        byte[] submitted = Polygons.rasterize(req.region(), g.width(), g.height());
        if (Polygons.covered(submitted) == 0) {
            throw ValidationException.of("EMPTY_REGION",
                    "the submitted polygon covers no canonical pixels");
        }

        if (req.baseVersion() > ws.eventVersion()) {
            throw ValidationException.of("VERSION_AHEAD",
                    "baseVersion " + req.baseVersion() + " is newer than server version "
                            + ws.eventVersion());
        }

        if (req.baseVersion() == ws.eventVersion()) {
            DecisionEvent event = buildEvent(svc, EventType.CREATE, req, ws.eventVersion(),
                    null, null);
            svc.appendEvent(event);
            return SubmitResult.applied(event, List.of());
        }

        // Stale base: find concurrent events added after the editor's base version.
        List<DecisionEvent> concurrent = ws.events().subList((int) req.baseVersion(),
                ws.events().size());
        List<Decision> activeConcurrent = activeAfterFold(concurrent, ws);

        byte[] overlap = new byte[submitted.length];
        DecisionEvent firstConflict = null;
        for (Decision d : activeConcurrent) {
            byte[] other = Polygons.rasterize(d.region(), g.width(), g.height());
            byte[] inter = Polygons.intersection(submitted, other);
            if (Polygons.covered(inter) > 0) {
                for (int i = 0; i < overlap.length; i++) {
                    if (inter[i] != 0) {
                        overlap[i] = 1;
                    }
                }
                if (firstConflict == null) {
                    firstConflict = ws.events().stream()
                            .filter(e -> e.eventId().equals(d.eventId())).findFirst().orElse(null);
                }
            }
        }

        byte[] free = Polygons.subtract(submitted, overlap);
        List<ConflictReport.AutoMergedRegion> merged = new ArrayList<>();
        List<DecisionEvent> applied = new ArrayList<>();
        if (Polygons.covered(free) > 0) {
            for (Polygon piece : Polygons.maskToRectPolygons(free, g.width(), g.height())) {
                SubmitDecisionRequest pieceReq = new SubmitDecisionRequest(
                        req.decisionType(), piece, req.sourceId(), req.canonicalKey(),
                        ws.eventVersion(), req.author(),
                        appendNote(req.note(), "auto-merged non-overlapping fragment"));
                DecisionEvent event = buildEvent(svc, EventType.CREATE, pieceReq,
                        ws.eventVersion(), null, null);
                svc.appendEvent(event);
                applied.add(event);
                merged.add(new ConflictReport.AutoMergedRegion(piece,
                        Polygons.covered(Polygons.rasterize(piece, g.width(), g.height()))));
            }
        }

        if (Polygons.covered(overlap) > 0) {
            List<Polygon> conflictPolygons = Polygons.maskToRectPolygons(
                    overlap, g.width(), g.height());
            ConflictReport report = new ConflictReport(
                    svc.workspace().eventVersion(),
                    firstConflict.eventId(),
                    firstConflict.author(),
                    conflictPolygons,
                    Polygons.covered(overlap),
                    merged,
                    "concurrent edit by '" + firstConflict.author() + "' overlaps on "
                            + Polygons.covered(overlap) + " pixel(s); non-overlapping fragments "
                            + "were merged, adjust the conflict polygons and resubmit");
            return SubmitResult.conflict(report, applied);
        }
        return SubmitResult.applied(
                applied.isEmpty() ? null : applied.get(0), merged);
    }

    /** Undo appends a reverse event; the original event is retained in the log. */
    public DecisionEvent undo(WorkspaceService svc, String eventId, String user) {
        Workspace ws = svc.workspace();
        DecisionEvent target = ws.events().stream()
                .filter(e -> e.eventId().equals(eventId)).findFirst()
                .orElseThrow(() -> ValidationException.of("EVENT_MISSING",
                        "no event " + eventId));
        if (target.type() == EventType.UNDO) {
            throw ValidationException.of("UNDO_OF_UNDO",
                    "undo an UNDO by using redo instead");
        }
        boolean currentlyActive = materialize(ws).stream().anyMatch(d -> d.eventId().equals(eventId));
        if (!currentlyActive) {
            throw ValidationException.of("EVENT_INACTIVE",
                    "event " + eventId + " is already undone");
        }
        DecisionEvent reverse = new DecisionEvent(
                ws.eventVersion() + 1, svc.newId(), EventType.UNDO,
                target.decisionType(), target.region(), target.sourceId(), target.canonicalKey(),
                ws.eventVersion(), user, Instant.now(svc.clock()),
                target.eventId(), null, "undo of " + target.eventId());
        svc.appendEvent(reverse);
        return reverse;
    }

    /** Redo re-applies an undone decision as a fresh forward event. */
    public DecisionEvent redo(WorkspaceService svc, String undoneEventId, String user) {
        Workspace ws = svc.workspace();
        DecisionEvent target = ws.events().stream()
                .filter(e -> e.eventId().equals(undoneEventId)).findFirst()
                .orElseThrow(() -> ValidationException.of("EVENT_MISSING",
                        "no event " + undoneEventId));
        boolean active = materialize(ws).stream().anyMatch(d -> d.eventId().equals(undoneEventId));
        if (active) {
            throw ValidationException.of("EVENT_ACTIVE", "event is already active");
        }
        DecisionEvent replay = new DecisionEvent(
                ws.eventVersion() + 1, svc.newId(), EventType.REDO,
                target.decisionType(), target.region(), target.sourceId(), target.canonicalKey(),
                ws.eventVersion(), user, Instant.now(svc.clock()),
                null, target.eventId(), "redo of " + target.eventId());
        svc.appendEvent(replay);
        return replay;
    }

    private List<Decision> activeAfterFold(List<DecisionEvent> tail, Workspace ws) {
        // Determine which of the tail events are effective considering the whole log prefix too.
        return materialize(ws).stream()
                .filter(d -> tail.stream().anyMatch(e -> e.eventId().equals(d.eventId())))
                .toList();
    }

    private void validate(WorkspaceService svc, SubmitDecisionRequest req) {
        ValidationException.Builder b = ValidationException.builder("DECISION_REJECTED");
        if (req.region() == null) {
            b.add("region polygon is required");
        } else {
            Geometry g = svc.workspace().canonicalGeometry();
            if (req.region().minX() < -0.5 || req.region().minY() < -0.5
                    || req.region().maxX() > g.width() - 0.5
                    || req.region().maxY() > g.height() - 0.5) {
                b.add("region polygon leaves the canonical image (" + g.width() + "x"
                        + g.height() + "): bbox [" + req.region().minX() + "," + req.region().minY()
                        + "]->[" + req.region().maxX() + "," + req.region().maxY() + "]");
            }
        }
        switch (req.decisionType()) {
            case ACCEPT_SOURCE -> {
                if (req.sourceId() == null || svc.workspace().source(req.sourceId()) == null) {
                    b.add("ACCEPT_SOURCE requires an existing sourceId");
                }
            }
            case CORRECTION -> {
                if (req.canonicalKey() == null || req.canonicalKey().isBlank()) {
                    b.add("CORRECTION requires a canonicalKey");
                }
            }
            case PENDING -> { }
        }
        if (req.author() == null || req.author().isBlank()) {
            b.add("author is required");
        }
        if (!b.isEmpty()) {
            throw b.build();
        }
    }

    private DecisionEvent buildEvent(WorkspaceService svc, EventType type,
                                     SubmitDecisionRequest req, long currentVersion,
                                     String undoes, String redoOf) {
        return new DecisionEvent(
                currentVersion + 1,
                svc.newId(),
                type,
                req.decisionType(),
                req.region(),
                req.sourceId(),
                req.canonicalKey(),
                req.baseVersion(),
                req.author(),
                Instant.now(svc.clock()),
                undoes,
                redoOf,
                req.note());
    }

    private String appendNote(String base, String suffix) {
        if (base == null || base.isBlank()) {
            return suffix;
        }
        return base + "; " + suffix;
    }

    /** Result of a submit attempt: applied cleanly or merged-with-conflict. */
    public record SubmitResult(List<DecisionEvent> applied, ConflictReport conflict) {
        static SubmitResult applied(DecisionEvent event, List<ConflictReport.AutoMergedRegion> m) {
            return new SubmitResult(event == null ? List.of() : List.of(event), null);
        }

        static SubmitResult conflict(ConflictReport report, List<DecisionEvent> merged) {
            return new SubmitResult(List.copyOf(merged), report);
        }

        public boolean hasConflict() {
            return conflict != null;
        }
    }
}
