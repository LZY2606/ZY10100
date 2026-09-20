package gsb;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.mask.Mask;
import gsb.service.AnalysisService;
import gsb.service.DecisionService;
import gsb.service.MappingService;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static gsb.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisAndDecisionTest {

    private Workspace scenario() throws Exception {
        WorkspaceStore store = newStore();
        Workspace ws = createImage(store, "img_an", 10, 10);
        addVocab(ws, "voc", "v1", "1", "tumor");
        addVocab(ws, "voc", "v2", "1", "tumor");
        register(ws, "s1", "algo-a");
        register(ws, "s2", "algo-b");

        Mask a = shapedMask(10, 10, (i) -> {
            int x = i % 10;
            int y = i / 10;
            if (x < 4 && y < 4) return 1;
            return 0;
        });
        Mask b = shapedMask(10, 10, (i) -> {
            int x = i % 10;
            int y = i / 10;
            if (x >= 2 && x < 6 && y < 4) return 1;
            if (x >= 8 && y >= 8) return 1;
            return 0;
        });
        addVersion(ws, "s1", "voc", "v1", a);
        addVersion(ws, "s2", "voc", "v2", b);
        MappingService.proposeForActiveLabels(ws);
        return ws;
    }

    @Test
    void sameNameAcrossVocabVersionsStartsUnmappedAndCreatesDisagreement() throws Exception {
        Workspace ws = scenario();
        Analysis an = AnalysisService.compute(ws);
        long disagree = an.summary.disagreePixels;
        assertTrue(disagree > 0, "unconfirmed same-name refs must not silently agree");
        assertEquals(1, ws.model().mappingGroups.size());
        assertEquals("PROPOSED", ws.model().mappingGroups.values().iterator().next().status);
    }

    @Test
    void confirmedMappingTurnsOverlapIntoAgreement() throws Exception {
        Workspace ws = scenario();
        String groupId = ws.model().mappingGroups.keySet().iterator().next();
        MappingService.confirm(ws, groupId, "tester");
        Analysis an = AnalysisService.compute(ws);
        assertEquals(0, an.summary.disagreePixels);
        assertTrue(an.summary.agreePixels >= 8);
    }

    @Test
    void confidenceThresholdMovesPixelsToUncovered() throws Exception {
        Workspace ws = scenario();
        AnalysisService.setThreshold(ws, 0.99, "tester");
        Analysis an = AnalysisService.compute(ws);
        assertEquals(0, an.summary.agreePixels + an.summary.disagreePixels);
        assertTrue(an.summary.uncoveredPixels > 0);
    }

    @Test
    void concurrentEditsOverlapReturnsConflictPolygonButMergesNonOverlap() throws Exception {
        Workspace ws = scenario();
        String groupId = ws.model().mappingGroups.keySet().iterator().next();
        MappingService.confirm(ws, groupId, "tester");
        long base = ws.log.nextSeq() - 1;

        List<List<Double>> p1 = List.of(
                List.of(0.0, 0.0), List.of(4.0, 0.0), List.of(4.0, 4.0), List.of(0.0, 4.0));
        List<List<Double>> p2 = List.of(
                List.of(2.0, 2.0), List.of(7.0, 2.0), List.of(7.0, 7.0), List.of(2.0, 7.0));

        DecisionService.Outcome first = DecisionService.record(ws, Json.obj(
                "kind", "PENDING", "baseVersion", base, "polygon", asJson(p1)), "user-a");
        assertFalse(first.conflicted);
        assertTrue(first.event.seq > base);

        DecisionService.Outcome second = DecisionService.record(ws, Json.obj(
                "kind", "PENDING", "baseVersion", base, "polygon", asJson(p2)), "user-b");
        assertTrue(second.conflicted);
        assertTrue(second.conflictPixels > 0);
        assertTrue(second.appliedPixels > 0, "non-overlapping part auto-merges");
        assertFalse(second.conflictPolygons.isEmpty(), "conflict polygon returned");
    }

    @Test
    void fullyOverlappingStaleEditIsRejectedWith409() throws Exception {
        Workspace ws = scenario();
        long base = ws.log.nextSeq() - 1;
        List<List<Double>> p = List.of(
                List.of(0.0, 0.0), List.of(3.0, 0.0), List.of(3.0, 3.0), List.of(0.0, 3.0));
        DecisionService.record(ws, Json.obj("kind", "PENDING",
                "baseVersion", base, "polygon", asJson(p)), "user-a");
        ApiException ex = assertThrows(ApiException.class, () -> DecisionService.record(ws,
                Json.obj("kind", "PENDING", "baseVersion", base, "polygon", asJson(p)),
                "user-b"));
        assertEquals(409, ex.status);
        assertEquals("EDIT_CONFLICT", ex.code);
    }

    @Test
    void undoProducesReverseEventAndKeepsHistory() throws Exception {
        Workspace ws = scenario();
        long base = ws.log.nextSeq() - 1;
        List<List<Double>> p = List.of(
                List.of(0.0, 0.0), List.of(2.0, 0.0), List.of(2.0, 2.0), List.of(0.0, 2.0));
        DecisionService.Outcome o = DecisionService.record(ws, Json.obj(
                "kind", "PENDING", "baseVersion", base, "polygon", asJson(p)), "user-a");
        long before = ws.model().decisions.size();
        DecisionService.undo(ws, o.event.payload.get("decisionId").toString(), "user-a");
        assertEquals(before + 1, ws.model().decisions.size());
        Model.Decision undone = ws.model().decisions.get(o.event.payload.get("decisionId"));
        assertNotNull(undone.undoneBy);
    }

    @Test
    void staleDecisionIsFlaggedAfterNewerSourceVersion() throws Exception {
        Workspace ws = scenario();
        long base = ws.log.nextSeq() - 1;
        List<List<Double>> p = List.of(
                List.of(0.0, 0.0), List.of(2.0, 0.0), List.of(2.0, 2.0), List.of(0.0, 2.0));
        DecisionService.Outcome o = DecisionService.record(ws, Json.obj(
                "kind", "ACCEPT", "acceptedSourceId", "s1",
                "baseVersion", base, "polygon", asJson(p)), "user-a");
        String decId = o.event.payload.get("decisionId").toString();

        Mask newer = shapedMask(10, 10, (i) -> i % 7 == 0 ? 1 : 0);
        addVersion(ws, "s1", "voc", "v1", newer);
        Model.Decision d = ws.model().decisions.get(decId);
        assertTrue(d.stale);
        assertNotNull(d.staleReason);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asJson(List<List<Double>> polygon) {
        return (List<Object>) (List<?>) polygon;
    }
}
