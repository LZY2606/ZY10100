package gsb;

import gsb.model.DecisionType;
import gsb.model.Polygon;
import gsb.service.ConsensusService;
import gsb.service.IngestRequest;
import gsb.service.IngestService;
import gsb.service.SubmitDecisionRequest;
import gsb.service.ValidationException;
import gsb.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionConcurrencyTest {

    private Path dir;
    private WorkspaceService svc;
    private ConsensusService consensus;
    private String sourceId;

    private Polygon rect(double x0, double y0, double x1, double y1) {
        return new Polygon(List.of(
                new double[]{x0, y0}, new double[]{x1, y0},
                new double[]{x1, y1}, new double[]{x0, y1}));
    }

    @BeforeEach
    void setUp() {
        dir = TestEnv.tempDir();
        svc = TestEnv.newWorkspace(dir);
        consensus = new ConsensusService();
        int[] labels = new int[48];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (i % 3 == 0) ? 1 : 0;
        }
        var res = new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false));
        sourceId = res.source().sourceId();
    }

    private SubmitDecisionRequest accept(Polygon region, long baseVersion, String user) {
        return new SubmitDecisionRequest(DecisionType.ACCEPT_SOURCE, region, sourceId,
                null, baseVersion, user, null);
    }

    @Test
    void twoUsersOnSameVersionOverlapProducesConflictPolygonsAndAutoMergesRest() {
        // user 1 and user 2 both start at version 0
        Polygon regionUser2 = rect(0.5, 0.5, 5.5, 3.5); // spans x 0..4
        // user 1 commits first on left strip x 0..1
        var first = consensus.submit(svc,
                accept(rect(0.5, 0.5, 2.5, 3.5), 0, "alice"));
        assertThat(first.hasConflict()).isFalse();
        assertThat(svc.workspace().eventVersion()).isEqualTo(1);

        // user 2 submits based on stale version 0
        var second = consensus.submit(svc, accept(regionUser2, 0, "bob"));
        assertThat(second.hasConflict()).isTrue();
        assertThat(second.conflict().conflictingAuthor()).isEqualTo("alice");
        // overlapping columns x=0,1 are conflict; columns x=2..4 auto-merged
        assertThat(second.conflict().conflictingPixels()).isEqualTo(3L * 3);
        assertThat(second.conflict().autoMerged()).isNotEmpty();
    }

    @Test
    void nonOverlappingConcurrentEditsMergeAutomatically() {
        consensus.submit(svc, accept(rect(0.5, 0.5, 2.5, 2.5), 0, "alice"));
        var bob = consensus.submit(svc, accept(rect(3.5, 3.5, 6.5, 5.5), 0, "bob"));
        assertThat(bob.hasConflict()).isFalse();
        assertThat(svc.workspace().eventVersion()).isEqualTo(2);
    }

    @Test
    void laterEditorCanReMergeAfterSeeingConflict() {
        consensus.submit(svc, accept(rect(0.5, 0.5, 3.5, 3.5), 0, "alice"));
        var conflict = consensus.submit(svc, accept(rect(0.5, 0.5, 5.5, 3.5), 0, "bob"));
        assertThat(conflict.hasConflict()).isTrue();

        // bob edits away the conflict and resubmits on the new server version
        long v = svc.workspace().eventVersion();
        var retry = consensus.submit(svc, accept(rect(3.5, 0.5, 5.5, 3.5), v, "bob"));
        assertThat(retry.hasConflict()).isFalse();
    }

    @Test
    void undoAppendsReverseEventAndRedoReplays() {
        var created = consensus.submit(svc, accept(rect(0.5, 0.5, 3.5, 3.5), 0, "alice"));
        String eventId = created.applied().get(0).eventId();
        long afterCreate = svc.workspace().eventVersion();

        var undo = consensus.undo(svc, eventId, "alice");
        assertThat(undo.type()).isEqualTo(gsb.model.EventType.UNDO);
        assertThat(undo.undoesEventId()).isEqualTo(eventId);
        assertThat(svc.workspace().eventVersion()).isEqualTo(afterCreate + 1);
        assertThat(consensus.materialize(svc.workspace()))
                .noneMatch(d -> d.eventId().equals(eventId));

        var redo = consensus.redo(svc, eventId, "alice");
        assertThat(redo.type()).isEqualTo(gsb.model.EventType.REDO);
        assertThat(consensus.materialize(svc.workspace())).isNotEmpty();
    }

    @Test
    void rejectsRegionOutsideImage() {
        assertThatThrownBy(() -> consensus.submit(svc,
                accept(rect(2, 2, 100, 100), 0, "alice")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("leaves the canonical image");
    }

    @Test
    void decisionsReferencingOldRevisionAreFlaggedStaleButKept() {
        var created = consensus.submit(svc, accept(rect(0.5, 0.5, 3.5, 3.5), 0, "alice"));
        String eventId = created.applied().get(0).eventId();

        // algorithm update -> new revision
        int[] labels = new int[48];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (i % 4 == 0) ? 1 : 0;
        }
        new IngestService().ingest(svc, new IngestRequest(
                "algo-a", sourceId, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV2(), 8, 6, labels, null, false));

        var decisions = consensus.materialize(svc.workspace());
        assertThat(decisions).anyMatch(d -> d.eventId().equals(eventId) && d.stale());
    }
}
