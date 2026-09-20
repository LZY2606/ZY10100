package gsb;

import gsb.model.DecisionType;
import gsb.model.Polygon;
import gsb.service.ConsensusService;
import gsb.service.IngestRequest;
import gsb.service.IngestService;
import gsb.service.SubmitDecisionRequest;
import gsb.service.WorkspaceService;
import gsb.storage.EventLog;
import gsb.storage.WorkspaceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryTest {

    @Test
    void tornAppendAtCrashIsDiscardedOnReopenAndEarlierEventsReplay() throws Exception {
        Path dir = TestEnv.tempDir();
        WorkspaceService svc = TestEnv.newWorkspace(dir);
        int[] labels = new int[48];
        labels[0] = 1;
        String source = new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false)).source().sourceId();

        Polygon region = new Polygon(List.of(
                new double[]{0.5, 0.5}, new double[]{3.5, 0.5},
                new double[]{3.5, 3.5}, new double[]{0.5, 3.5}));
        new ConsensusService().submit(svc, new SubmitDecisionRequest(
                DecisionType.ACCEPT_SOURCE, region, source, null, 0, "alice", null));
        assertThat(svc.workspace().eventVersion()).isEqualTo(1);

        // Simulate a crash with a partial (torn, bad-checksum) final line.
        Path log = dir.resolve("workspaces").resolve("ws").resolve("events.log");
        Files.writeString(log, "{\"torn\":true,partial", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        // Reopen: snapshot cache ignored, log replayed and repaired.
        WorkspaceService reopened = new WorkspaceService(new WorkspaceStore(dir),
                TestEnv.fixedClock());
        reopened.open("ws");
        assertThat(reopened.workspace().eventVersion()).isEqualTo(1);
        assertThat(new ConsensusService().materialize(reopened.workspace())).hasSize(1);
        assertThat(reopened.recoveryNote()).contains("torn event line");

        // Log file now ends at the intact prefix.
        String tail = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(tail).doesNotContain("torn");
    }

    @Test
    void missingBlobIsDetectedViaContentHash() throws Exception {
        Path dir = TestEnv.tempDir();
        WorkspaceService svc = TestEnv.newWorkspace(dir);
        int[] labels = new int[48];
        labels[0] = 1;
        var result = new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false));
        byte[] blob = svc.store().blobs().get(result.source().maskBlobSha256());
        // tamper on disk by writing different bytes to the content-addressed path
        Path blobPath = dir.resolve("blobs")
                .resolve(result.source().maskBlobSha256().substring(0, 2))
                .resolve(result.source().maskBlobSha256() + ".bin");
        Files.write(blobPath, new byte[]{1, 2, 3, 4});
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> svc.store().blobs().get(result.source().maskBlobSha256())))
                .hasMessageContaining("blob corrupt");
    }
}
