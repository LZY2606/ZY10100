package gsb;

import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import gsb.service.IngestService;
import gsb.store.ApiException;
import gsb.store.EvidenceStore;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.util.Hash;
import org.junit.jupiter.api.Test;

import static gsb.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class IngestValidationTest {

    @Test
    void matchingWidthHeightDoesNotBypassFingerprintMismatch() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = store.create("img_fp");
        byte[] png = png(4, 4, 1);
        byte[] other = png(4, 4, 2);
        ApiException ex = assertThrows(ApiException.class, () -> IngestService.createImage(
                ws, gsb.json.Json.obj("name", "x", "width", 4, "height", 4,
                        "orientation", 0, "spacingX", 1.0, "spacingY", 1.0,
                        "fingerprint", Hash.sha256Hex(other)), png, "tester"));
        assertEquals(422, ex.status);
        assertEquals("FINGERPRINT_MISMATCH", ex.code);
        assertTrue(ex.getMessage().contains("comparison refused"));
        assertNull(ws.model().image);
    }

    @Test
    void dimensionMismatchIsRejectedBeforeComparison() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = store.create("img_dim");
        byte[] png = png(4, 4, 1);
        ApiException ex = assertThrows(ApiException.class, () -> IngestService.createImage(
                ws, gsb.json.Json.obj("name", "x", "width", 5, "height", 4,
                        "orientation", 0, "spacingX", 1.0, "spacingY", 1.0,
                        "fingerprint", Hash.sha256Hex(png)), png, "tester"));
        assertEquals("DIMENSION_MISMATCH", ex.code);
    }

    @Test
    void spacingMismatchBlocksMaskImport() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_sp", 8, 8);
        addVocab(ws, "v", "1", "1", "a");
        register(ws, "s1", "algo");
        Mask mask = shapedMask(8, 8, (i) -> i % 5 == 0 ? 1 : 0);
        byte[] bytes = MrleCodec.encode(mask);
        ApiException ex = assertThrows(ApiException.class, () -> TestFixtures.addVersionAniso(ws,
                "s1", "v", "1", mask, 1.3, 1.7));
        assertEquals("SPACING_MISMATCH", ex.code);
    }

    @Test
    void labelOutsideVocabularyIsRejectedWithDiagnostics() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_lab", 8, 8);
        addVocab(ws, "v", "1", "1", "only-one");
        register(ws, "s1", "algo");
        Mask mask = shapedMask(8, 8, (i) -> i % 4 == 0 ? 2 : 0);
        byte[] bytes = MrleCodec.encode(mask);
        ApiException ex = assertThrows(ApiException.class, () ->
                addVersion(ws, "s1", "v", "1", mask));
        assertEquals("LABEL_OUT_OF_VOCABULARY", ex.code);
        assertFalse(ex.details.isEmpty());
    }

    @Test
    void rawEvidenceIsImmutableContentAddressed() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_ev", 4, 4);
        byte[] png = png(4, 4, 1);
        byte[] again = png;
        EvidenceStore.Stored a = ws.evidence.put(png, Hash.sha256Hex(png));
        EvidenceStore.Stored b = ws.evidence.put(again, Hash.sha256Hex(again));
        assertEquals(a.sha256(), b.sha256());
        assertThrows(EvidenceStore.FingerprintMismatchException.class,
                () -> ws.evidence.put(png, "0".repeat(64)));
    }
}
