package gsb.service;

import gsb.crypto.Hashes;
import gsb.geom.Geometry;
import gsb.geom.Polygons;
import gsb.model.ClassMapping;
import gsb.model.Decision;
import gsb.model.DecisionType;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.rle.RleCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Synthesises the consensus raster and its deterministic boundary hash from events+sources. */
public final class ConsensusBuilder {

    public BuiltRaster build(WorkspaceService svc) {
        Workspace ws = svc.workspace();
        Geometry g = ws.canonicalGeometry();
        int width = g.width();
        int height = g.height();
        int pixels = width * height;
        int[] labels = new int[pixels];
        byte[] edges = new byte[pixels];
        boolean[] decided = new boolean[pixels];

        ConsensusService consensus = new ConsensusService();
        List<Decision> decisions = consensus.materialize(ws);
        long stalePixels = 0;

        for (Decision d : decisions) {
            byte[] region = Polygons.rasterize(d.region(), width, height);
            boolean stale = d.stale();
            switch (d.decisionType()) {
                case ACCEPT_SOURCE -> {
                    Source src = ws.source(d.sourceId());
                    RleCodec.Grid sg = RleCodec.decode(
                            svc.store().blobs().get(src.maskBlobSha256()));
                    for (int i = 0; i < pixels; i++) {
                        if (region[i] != 0) {
                            // paint canonical key id; use a synthetic stable code for boundary hash
                            labels[i] = sg.labels()[i];
                            edges[i] = sg.edgeFlags()[i];
                            decided[i] = true;
                            if (stale) {
                                stalePixels++;
                            }
                        }
                    }
                }
                case CORRECTION -> {
                    int synthetic = syntheticClassId(d.canonicalKey());
                    for (int i = 0; i < pixels; i++) {
                        if (region[i] != 0) {
                            labels[i] = synthetic;
                            decided[i] = true;
                            if (stale) {
                                stalePixels++;
                            }
                        }
                    }
                }
                case PENDING -> {
                    for (int i = 0; i < pixels; i++) {
                        if (region[i] != 0) {
                            labels[i] = 0;
                            decided[i] = true;
                        }
                    }
                }
            }
        }

        long regionPixels = 0;
        for (boolean b : decided) {
            if (b) {
                regionPixels++;
            }
        }
        String boundaryHash = boundaryHash(width, height, labels, edges, decided,
                ws.ruleSetVersion());
        return new BuiltRaster(width, height, labels, edges, regionPixels,
                boundaryHash, stalePixels);
    }

    /** Stable positive synthetic class id from canonical key hash (1..0x7fffffff). */
    public static int syntheticClassId(String canonicalKey) {
        int h = java.util.Arrays.hashCode(
                Hashes.digest(canonicalKey.getBytes(StandardCharsets.UTF_8)));
        return Math.abs(h) + 1;
    }

    /**
     * Boundary hash: for every pixel that sits next to a differing label, feed a deterministic
     * row/col/label triple. Captures the segmentation outline so resampling drift or edits change
     * the hash, and re-import can prove equality.
     */
    public static String boundaryHash(int width, int height, int[] labels, byte[] edges,
                                      boolean[] decided, String ruleVersion) {
        MessageDigest md = Hashes.sha256();
        md.update(ruleVersion.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int v = labels[i];
                boolean boundary = false;
                if (x + 1 < width && labels[i + 1] != v) {
                    boundary = true;
                }
                if (!boundary && y + 1 < height && labels[i + width] != v) {
                    boundary = true;
                }
                if (boundary && decided[i]) {
                    md.update(intBytes(x));
                    md.update(intBytes(y));
                    md.update(intBytes(v));
                    md.update(new byte[]{edges[i]});
                }
            }
        }
        return Hashes.hex(md.digest());
    }

    private static byte[] intBytes(int v) {
        return new byte[]{
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}
