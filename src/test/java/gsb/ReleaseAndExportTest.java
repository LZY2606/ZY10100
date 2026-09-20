package gsb;

import gsb.model.DecisionType;
import gsb.model.Polygon;
import gsb.model.ReleaseSnapshot;
import gsb.service.ConsensusBuilder;
import gsb.service.ConsensusService;
import gsb.service.ExportService;
import gsb.service.IngestRequest;
import gsb.service.IngestService;
import gsb.service.MappingService;
import gsb.service.ReleaseService;
import gsb.service.SubmitDecisionRequest;
import gsb.service.ValidationException;
import gsb.service.WorkspaceService;
import gsb.storage.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseAndExportTest {

    private Path dir;
    private WorkspaceService svc;
    private String sourceA;
    private String sourceB;

    private int[] mask(int seed) {
        int[] labels = new int[48];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (i % seed == 0) ? 1 : (i % 5 == 0 ? 2 : 0);
        }
        return labels;
    }

    @BeforeEach
    void setUp() {
        dir = TestEnv.tempDir();
        svc = TestEnv.newWorkspace(dir);
        sourceA = new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, mask(3), null, false)).source().sourceId();
        sourceB = new IngestService().ingest(svc, new IngestRequest(
                "algo-b", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV2(), 8, 6, mask(4), null, false)).source().sourceId();
    }

    private void confirmAll() {
        MappingService mappingService = new MappingService();
        var all = List.copyOf(svc.workspace().mappings().values());
        for (var m : all) {
            mappingService.confirm(svc, m.mappingId(), "reviewer");
        }
    }

    @Test
    void releaseBlockedUntilAllMappingsConfirmed() {
        var gate = new ReleaseService().evaluate(svc);
        assertThat(gate.allowed).isFalse();
        assertThat(gate.problems.toString()).contains("unconfirmed class mapping");

        confirmAll();
        assertThat(new ReleaseService().evaluate(svc).allowed).isTrue();
    }

    @Test
    void releaseRecordsFingerprintsRuleVersionBoundaryHash() {
        confirmAll();
        new ConsensusService().submit(svc, new SubmitDecisionRequest(
                DecisionType.ACCEPT_SOURCE,
                new Polygon(List.of(new double[]{0.5, 0.5}, new double[]{4.5, 0.5},
                        new double[]{4.5, 3.5}, new double[]{0.5, 3.5})),
                sourceA, null, 0, "alice", null));
        ReleaseSnapshot release = new ReleaseService().publish(svc, "alice");
        assertThat(release.versionNo()).isEqualTo(1);
        assertThat(release.sourceFingerprints()).containsExactly(DomainFixtures.FINGERPRINT_A);
        assertThat(release.ruleSetVersion()).isEqualTo("gsb-ruleset-1");
        assertThat(release.boundaryHash()).hasSize(64);
        assertThat(release.regionAreaPixels()).isPositive();
    }

    @Test
    void exportThenReimportKeepsRegionAreaAndBoundaryHash() {
        confirmAll();
        ConsensusService consensus = new ConsensusService();
        consensus.submit(svc, new SubmitDecisionRequest(
                DecisionType.CORRECTION,
                new Polygon(List.of(new double[]{1.5, 1.5}, new double[]{5.5, 1.5},
                        new double[]{5.5, 4.5}, new double[]{1.5, 4.5})),
                null, "class:tumor", 0, "alice", "drawn fix"));
        BuiltRasterExpected before = snapshot();

        byte[] zip = new ExportService().exportBundle(svc, null);

        Path dir2 = TestEnv.tempDir();
        WorkspaceService restored = new WorkspaceService(new WorkspaceStore(dir2),
                TestEnv.fixedClock());
        var report = new ExportService().reimport(restored, "ws2", "Restored", zip);
        assertThat(report.accepted()).as(report.problems()::toString).isTrue();
        assertThat(report.regionPixels()).isEqualTo(before.area);
        assertThat(report.boundaryHash()).isEqualTo(before.hash);
    }

    @Test
    void tamperedBlobIsRejectedOnImport() {
        byte[] zip = new ExportService().exportBundle(svc, null);
        // corrupt several bytes in the middle of the compressed stream (the tail is zip padding)
        for (int i = 40; i < 56; i++) {
            zip[i] ^= (byte) 0xFF;
        }
        Path dir2 = TestEnv.tempDir();
        WorkspaceService restored = new WorkspaceService(new WorkspaceStore(dir2),
                TestEnv.fixedClock());
        assertThatThrownBy(() -> new ExportService().reimport(restored, "ws3", "Bad", zip))
                .isInstanceOf(Exception.class);
    }

    private BuiltRasterExpected snapshot() {
        var r = new ConsensusBuilder().build(svc);
        return new BuiltRasterExpected(r.regionPixels(), r.boundaryHash());
    }

    private record BuiltRasterExpected(long area, String hash) {}
}
