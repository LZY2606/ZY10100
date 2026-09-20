package gsb;

import gsb.analysis.Analysis;
import gsb.geo.Geometry;
import gsb.json.Json;
import gsb.mask.Mask;
import gsb.service.AnalysisService;
import gsb.service.ExportService;
import gsb.service.ImportService;
import gsb.service.MappingService;
import gsb.service.ReleaseService;
import gsb.store.ApiException;
import gsb.store.EventLog;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.util.Hash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static gsb.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseExportRecoveryTest {

    private Workspace readyForRelease() throws Exception {
        return readyForRelease(newStore(), "img_rel");
    }

    private Workspace readyForRelease(WorkspaceStore store, String imageId) throws Exception {
        Workspace ws = createImage(store, imageId, 10, 10);
        addVocab(ws, "voc", "v1", "1", "tumor");
        addVocab(ws, "voc", "v2", "1", "tumor");
        register(ws, "s1", "algo-a");
        register(ws, "s2", "algo-b");
        Mask a = shapedMask(10, 10, (i) -> {
            int x = i % 10;
            int y = i / 10;
            return (x < 5 && y < 5) ? 1 : 0;
        });
        Mask b = shapedMask(10, 10, (i) -> {
            int x = i % 10;
            int y = i / 10;
            return (x >= 2 && x < 7 && y >= 2 && y < 7) ? 1 : 0;
        });
        addVersion(ws, "s1", "voc", "v1", a);
        addVersion(ws, "s2", "voc", "v2", b);
        MappingService.proposeForActiveLabels(ws);
        MappingService.confirm(ws, ws.model().mappingGroups.keySet().iterator().next(), "tester");
        return ws;
    }

    @Test
    void releaseBlockedByUnconfirmedMappingsThenPassesAfterConfirm() throws Exception {
        Workspace ws = readyForRelease();
        // Undo the confirm: create a fresh scenario without confirming instead.
        Workspace ws2 = unconfirmedWorkspace();
        ApiException ex = assertThrows(ApiException.class,
                () -> ReleaseService.publish(ws2, "tester"));
        assertEquals("RELEASE_BLOCKED", ex.code);
        assertTrue(ex.details.stream().anyMatch(d -> d.contains("mapping not confirmed")));

        Map<String, Object> r = ReleaseService.publish(ws, "tester");
        assertNotNull(r.get("manifestHash"));
        assertEquals(1, ws.model().releases.size());
    }

    private Workspace unconfirmedWorkspace() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_block", 6, 6);
        addVocab(ws, "voc", "v1", "1", "tumor");
        addVocab(ws, "voc", "v2", "1", "tumor");
        register(ws, "s1", "a");
        register(ws, "s2", "b");
        Mask a = shapedMask(6, 6, (i) -> i % 6 < 3 ? 1 : 0);
        Mask b = shapedMask(6, 6, (i) -> i % 6 >= 1 ? 1 : 0);
        addVersion(ws, "s1", "voc", "v1", a);
        addVersion(ws, "s2", "voc", "v2", b);
        MappingService.proposeForActiveLabels(ws);
        return ws;
    }

    @Test
    void releaseBlockedByOutOfBoundsPixels() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_oob", 6, 6);
        addVocab(ws, "voc", "v1", "1", "tumor");
        register(ws, "s1", "a");
        // 10x6 native mask rotated 90 degrees produces a 6x10 aligned frame; the
        // target image is only 6x6, so the extra 4 rows are OOB claims.
        Mask big = shapedMask(10, 6, (i) -> 1);
        gsb.store.Model.SourceVersion v = addVersion(ws, "s1", "voc", "v1", big,
                90, 1.0, 10, 6);
        assertTrue(v.outOfBoundsPixels > 0,
                "expected OOB pixels but got " + v.outOfBoundsPixels);
        ApiException ex = assertThrows(ApiException.class,
                () -> ReleaseService.publish(ws, "tester"));
        assertEquals("RELEASE_BLOCKED", ex.code);
        assertTrue(ex.details.stream().anyMatch(d -> d.contains("out-of-bounds")));
    }

    @Test
    void exportThenImportKeepsAreasAndBoundaryHashes() throws Exception {
        Workspace ws = readyForRelease();
        Analysis before = AnalysisService.compute(ws);
        assertFalse(before.regions.isEmpty());
        byte[] zip = ExportService.exportZip(ws);
        Map<String, Object> verification = ExportService.verifyZip(zip);
        assertEquals(Boolean.TRUE, verification.get("ok"),
                () -> verification.toString());

        WorkspaceStore imported = newStore();
        Map<String, Object> result = ImportService.importBundle(imported, zip, "img_copy", "tester");
        assertEquals(Boolean.TRUE, result.get("ok"), () -> result.toString());

        Workspace copy = imported.open("img_copy");
        Analysis after = AnalysisService.compute(copy);
        assertEquals(before.regions.size(), after.regions.size());
        for (int i = 0; i < before.regions.size(); i++) {
            assertEquals(before.regions.get(i).pixelCount, after.regions.get(i).pixelCount);
            assertEquals(before.regions.get(i).boundaryHash, after.regions.get(i).boundaryHash);
            assertEquals(before.regions.get(i).kindName(), after.regions.get(i).kindName());
        }
        assertEquals(before.summary.agreePixels, after.summary.agreePixels);
        assertEquals(before.summary.boundaryPixels, after.summary.boundaryPixels);
        assertEquals(ws.model().image.fingerprint, copy.model().image.fingerprint);
    }

    @Test
    void tamperedExportFailsVerification() throws Exception {
        Workspace ws = readyForRelease();
        byte[] zip = ExportService.exportZip(ws);
        byte[] tampered = corruptFirstDeflateEntry(zip);
        assertThrows(ApiException.class, () -> ExportService.verifyZip(tampered));
    }

    @Test
    void brokenEventChainFailsFastAndRecoveryTruncatesAfterLastValid() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = readyForRelease(store, "img_rel");
        Path logFile = ws.log.file();
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertTrue(lines.size() > 3);
        // Corrupt the last line's payload (keep valid JSON so hash check fires).
        String last = lines.get(lines.size() - 1);
        Map<String, Object> parsed = Json.parseObject(last);
        parsed.put("tampered", true);
        Files.write(logFile, (String.join("\n", lines.subList(0, lines.size() - 1))
                + "\n" + Json.write(parsed) + "\n").getBytes(StandardCharsets.UTF_8));

        Workspace reopened = new Workspace(ws.imageId,
                store.root().resolve("images").resolve(ws.imageId));
        assertThrows(EventLog.ChainBrokenException.class, reopened::model);

        Path backup = ws.dir.resolve("events.backup");
        int removed = new EventLog(logFile).truncateAfterLastValid(backup);
        assertEquals(1, removed);
        assertTrue(Files.exists(backup));

        Workspace recovered = store.open(ws.imageId);
        assertNotNull(recovered.model().image);
        long expectedSeq = lines.size() - 1L;
        assertEquals(expectedSeq, recovered.log.nextSeq() - 1);
    }

    private static byte[] corruptFirstDeflateEntry(byte[] zip) throws Exception {
        // Local file header signature PK\x03\x04; skip the 30-byte header and
        // the variable name/extra fields, then flip a mid-stream payload byte.
        byte[] sig = {0x50, 0x4b, 0x03, 0x04};
        int p = -1;
        outer:
        for (int i = 0; i < zip.length - 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (zip[i + j] != sig[j]) continue outer;
            }
            p = i;
            break;
        }
        assertTrue(p >= 0, "zip must contain a local file header");
        int nameLen = (zip[p + 26] & 255) | ((zip[p + 27] & 255) << 8);
        int extraLen = (zip[p + 28] & 255) | ((zip[p + 29] & 255) << 8);
        int dataStart = p + 30 + nameLen + extraLen;
        byte[] out = zip.clone();
        out[dataStart + 8] ^= 0x5a;
        return out;
    }

    private static int indexOfAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Test
    void derivedMaskCarriesRuleVersionAndSourceFingerprint() throws Exception {
        Workspace ws = readyForRelease();
        for (gsb.store.Model.SourceVersion v : ws.model().activeVersions()) {
            assertEquals(Analysis.RULE_VERSION, v.derivedFromRuleVersion);
            assertEquals(64, v.derivedFingerprint.length());
            byte[] derived = ws.evidence.read(v.derivedEvidence);
            assertEquals(v.derivedFingerprint, Hash.sha256Hex(derived));
        }
    }
}
