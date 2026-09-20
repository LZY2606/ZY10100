package gsb;

import gsb.model.ComparisonParams;
import gsb.model.ComparisonResult;
import gsb.service.ComparisonService;
import gsb.service.IngestRequest;
import gsb.service.IngestService;
import gsb.service.MappingService;
import gsb.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonTest {

    private Path dir;
    private WorkspaceService svc;
    private String a;
    private String b;

    @BeforeEach
    void setUp() {
        dir = TestEnv.tempDir();
        svc = TestEnv.newWorkspace(dir);
        int[] la = new int[48];
        int[] lb = new int[48];
        for (int i = 0; i < 48; i++) {
            int x = i % 8;
            la[i] = x < 4 ? 1 : 2;
            lb[i] = x < 4 ? 1 : 1; // differs on right half
        }
        a = new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, la, null, false)).source().sourceId();
        b = new IngestService().ingest(svc, new IngestRequest(
                "algo-b", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV2(), 8, 6, lb, null, false)).source().sourceId();

        MappingService mappingService = new MappingService();
        for (var m : List.copyOf(svc.workspace().mappings().values())) {
            mappingService.confirm(svc, m.mappingId(), "reviewer");
        }
    }

    @Test
    void countsAgreeDisagreeUncoveredAndCarriesRuleAndFingerprints() {
        ComparisonResult r = new ComparisonService().compare(svc,
                new ComparisonParams(0.0, List.of(a, b), false));
        // left 4 cols of 6 rows agree (24); right 4 cols disagree (24)
        assertThat(r.stats().agreePixels()).isEqualTo(24);
        assertThat(r.stats().disagreePixels()).isEqualTo(24);
        assertThat(r.ruleVersion()).isEqualTo(ComparisonParams.RULE_VERSION);
        assertThat(r.sourceFingerprints()).hasSize(2);
    }

    @Test
    void confidenceThresholdExcludesLowConfidencePixels() {
        // give source B low confidence everywhere
        double[] low = DomainFixtures.confidenceAll(8, 6, 0.2);
        // reingest a fresh revision of b with explicit confidence
        int[] lb = new int[48];
        for (int i = 0; i < 48; i++) {
            lb[i] = 1;
        }
        String bLow = new IngestService().ingest(svc, new IngestRequest(
                "algo-c", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, lb, low, false)).source().sourceId();
        var mappings = List.copyOf(svc.workspace().mappings().values());
        MappingService ms = new MappingService();
        for (var m : mappings) {
            if (m.sourceId().equals(bLow) && !m.confirmed()) {
                ms.confirm(svc, m.mappingId(), "reviewer");
            }
        }
        ComparisonResult r = new ComparisonService().compare(svc,
                new ComparisonParams(0.5, List.of(a, bLow), false));
        assertThat(r.stats().excludedByConfidencePixels()).isEqualTo(48);
        assertThat(r.stats().disagreePixels()).isZero();
    }

    @Test
    void sameNamedButUnconfirmedMappingsStillDisagree() {
        // new workspace where mappings are left unconfirmed
        Path d2 = TestEnv.tempDir();
        WorkspaceService s2 = TestEnv.newWorkspace(d2);
        int[] labels = new int[48];
        java.util.Arrays.fill(labels, 1);
        String x = new IngestService().ingest(s2, new IngestRequest(
                "algo-x", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels.clone(), null, false)).source().sourceId();
        String y = new IngestService().ingest(s2, new IngestRequest(
                "algo-y", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV2(), 8, 6, labels.clone(), null, false)).source().sourceId();
        ComparisonResult r = new ComparisonService().compare(s2,
                new ComparisonParams(0.0, List.of(x, y), false));
        // without confirmed mapping, every labeled pixel disagrees
        assertThat(r.stats().agreePixels()).isZero();
        assertThat(r.stats().disagreePixels()).isEqualTo(48);
    }
}
