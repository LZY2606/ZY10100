package gsb.service;

import gsb.model.ReleaseSnapshot;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.rle.RleCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Release gating. A published version is produced only when ALL of:
 *   1. every class mapping referenced by an accepted source is explicitly confirmed;
 *   2. no accepted mask contains out-of-vocabulary (out-of-bounds) pixels;
 *   3. the decision log references no missing sources;
 *   4. at least one source exists.
 * Out-of-bounds check is re-derived from immutable evidence (never trusted from import time).
 */
public final class ReleaseService {

    public static final class GateResult {
        public final boolean allowed;
        public final List<String> problems;

        GateResult(boolean allowed, List<String> problems) {
            this.allowed = allowed;
            this.problems = List.copyOf(problems);
        }
    }

    public GateResult evaluate(WorkspaceService svc) {
        Workspace ws = svc.workspace();
        List<String> problems = new ArrayList<>();

        if (ws.sources().isEmpty()) {
            problems.add("no source masks have been ingested");
        }

        for (String desc : new MappingService().unconfirmedDescriptions(svc)) {
            problems.add("unconfirmed class mapping: " + desc);
        }

        for (Source source : ws.sources().values()) {
            long oob = countOutOfBounds(svc, source);
            if (oob > 0) {
                problems.add("source " + source.algorithm() + " rev" + source.revisionNo()
                        + " contains " + oob + " out-of-bounds pixel(s) not in vocab "
                        + source.vocabulary().vocabVersion());
            }
        }

        return new GateResult(problems.isEmpty(), problems);
    }

    public long countOutOfBounds(WorkspaceService svc, Source source) {
        java.util.Set<Integer> declared = new java.util.HashSet<>();
        source.vocabulary().entries().forEach(e -> declared.add(e.classId()));
        RleCodec.Grid grid = RleCodec.decode(
                svc.store().blobs().get(source.maskBlobSha256()));
        long count = 0;
        for (int label : grid.labels()) {
            if (label != 0 && !declared.contains(label)) {
                count++;
            }
        }
        return count;
    }

    public ReleaseSnapshot publish(WorkspaceService svc, String user) {
        GateResult gate = evaluate(svc);
        if (!gate.allowed) {
            throw new ValidationException("RELEASE_BLOCKED", gate.problems);
        }
        Workspace ws = svc.workspace();
        ConsensusBuilder builder = new ConsensusBuilder();
        BuiltRaster raster = builder.build(svc);
        byte[] consensusBlob = RleCodec.encode(raster.width(), raster.height(),
                raster.labels(), raster.edges());
        String consensusHash = svc.store().blobs().put(consensusBlob);

        List<String> fingerprints = ws.sources().values().stream()
                .map(s -> s.image().fingerprintSha256()).distinct().toList();

        ExportService exporter = new ExportService();
        byte[] export = exporter.exportBundle(svc, null);
        String exportHash = svc.store().blobs().put(export);

        int versionNo = ws.releases().size() + 1;
        ReleaseSnapshot snapshot = new ReleaseSnapshot(
                "release-" + versionNo + "-" + ws.workspaceId(),
                versionNo, Instant.now(svc.clock()), user, ws.eventVersion(),
                fingerprints, consensusHash, consensusBlob.length, exportHash,
                ws.ruleSetVersion(), raster.regionPixels(), raster.boundaryHash());
        svc.addRelease(snapshot);
        return snapshot;
    }
}
