package gsb;

import gsb.geom.Geometry;
import gsb.model.Source;
import gsb.service.IngestRequest;
import gsb.service.IngestResult;
import gsb.service.IngestService;
import gsb.service.MappingService;
import gsb.service.ValidationException;
import gsb.service.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeometryAndIngestTest {

    private final Path dir = TestEnv.tempDir();
    private final WorkspaceService svc = TestEnv.newWorkspace(dir);

    private int[] blank(int n) {
        return new int[n];
    }

    @Test
    void rejectsDifferentFingerprintEvenWithSameDimensions() {
        Geometry same = DomainFixtures.geom();
        IngestRequest req = new IngestRequest(
                "algo-a", null, DomainFixtures.imageB(), same,
                DomainFixtures.vocabV1(), 8, 6, blank(48), null, false);
        assertThatThrownBy(() -> new IngestService().ingest(svc, req))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> {
                    ValidationException ve = (ValidationException) e;
                    assertThat(ve.code).isEqualTo("INGEST_REJECTED");
                    assertThat(String.join(" ", ve.reasons)).contains("fingerprint");
                });
    }

    @Test
    void acceptsMatchingMaskAndKeepsImmutableEvidence() {
        int[] labels = new int[48];
        labels[0] = 1;
        IngestRequest req = new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false);
        IngestResult result = new IngestService().ingest(svc, req);

        Source s = result.source();
        assertThat(s.revisionNo()).isEqualTo(1);
        assertThat(s.image().fingerprintSha256()).isEqualTo(DomainFixtures.FINGERPRINT_A);
        // blob is content-addressed and readable
        assertThat(svc.store().blobs().exists(s.maskBlobSha256())).isTrue();
        assertThat(svc.store().blobs().exists(s.confidenceBlobSha256())).isTrue();
    }

    @Test
    void detectsSpacingAndOrientationMismatchIndependentlyOfSize() {
        Geometry rotated = new Geometry(8, 6, 0.5, 0.5, "R|P", 0, 0);
        IngestRequest req = new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), rotated,
                DomainFixtures.vocabV1(), 8, 6, blank(48), null, false);
        assertThatThrownBy(() -> new IngestService().ingest(svc, req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orientation mismatch");
    }

    @Test
    void requiresExplicitResampleForDifferentRaster() {
        Geometry hiRes = new Geometry(16, 12, 0.25, 0.25, "L|A", 0, 0);
        IngestRequest req = new IngestRequest(
                "algo-hi", null, DomainFixtures.imageA(), hiRes,
                DomainFixtures.vocabV1(), 16, 12, blank(192), null, false);
        assertThatThrownBy(() -> new IngestService().ingest(svc, req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("RESAMPLE_REQUIRED");
    }

    @Test
    void resamplesAndFlagsBoundaryPixels() {
        // 16x12 grid covers the same physical 3.5x2.5 mm extent as the 8x6 canonical.
        Geometry hiRes = new Geometry(16, 12, 0.25, 0.25, "L|A", 0, 0);
        int[] labels = blank(192);
        // left half label 1, right half label 2 -> vertical boundary through the middle
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) {
                labels[y * 16 + x] = x < 8 ? 1 : 2;
            }
        }
        IngestRequest req = new IngestRequest(
                "algo-hi", null, DomainFixtures.imageA(), hiRes,
                DomainFixtures.vocabV1(), 16, 12, labels, null, true);
        IngestResult result = new IngestService().ingest(svc, req);
        assertThat(result.resampled()).isTrue();
        assertThat(result.warnings().get(0)).contains("boundary pixels flagged");
        assertThat(result.source().geometry()).isEqualTo(DomainFixtures.geom());
    }

    @Test
    void rejectsOutOfVocabularyPixelsAtIngest() {
        int[] labels = blank(48);
        labels[10] = 99; // not in vocabulary
        IngestRequest req = new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false);
        assertThatThrownBy(() -> new IngestService().ingest(svc, req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("out-of-bounds");
    }

    @Test
    void sameClassNameDifferentVocabVersionIsProposedButNotConfirmed() {
        int[] labels = blank(48);
        labels[0] = 1;
        new IngestService().ingest(svc, new IngestRequest(
                "algo-a", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV1(), 8, 6, labels, null, false));
        new IngestService().ingest(svc, new IngestRequest(
                "algo-b", null, DomainFixtures.imageA(), DomainFixtures.geom(),
                DomainFixtures.vocabV2(), 8, 6, labels, null, false));
        List<String> unconfirmed = new MappingService().unconfirmedDescriptions(svc);
        assertThat(unconfirmed).anyMatch(s -> s.contains("vocab-1"));
        assertThat(unconfirmed).anyMatch(s -> s.contains("vocab-2"));
    }
}
