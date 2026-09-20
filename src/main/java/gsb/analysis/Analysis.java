package gsb.analysis;

import gsb.geo.Components;
import gsb.geo.Contour;
import gsb.geo.Poly;
import gsb.mask.Mask;
import gsb.store.Model;
import gsb.util.Hash;
import gsb.util.Hex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pair-wise consensus engine, rule version {@code pairwise-gsb-v1}.
 *
 * <p>Per pixel, each active source version contributes either nothing
 * (uncovered or below confidence threshold) or a concept key resolved through
 * confirmed mapping groups:
 * <ul>
 *   <li><b>AGREE</b>: at least one claim and every claim shares one concept.</li>
 *   <li><b>DISAGREE</b>: two or more distinct concepts claimed.</li>
 *   <li><b>UNCOVERED</b>: no claims pass the threshold.</li>
 * </ul>
 * The derived raster carries rule version + source fingerprints; resampled
 * boundary pixels and OOB claims are tracked separately.
 */
public final class Analysis {
    public static final String RULE_VERSION = "pairwise-gsb-v1";

    public static final byte UNCOVERED = 0;
    public static final byte AGREE = 1;
    public static final byte DISAGREE = 2;

    public final int width;
    public final int height;
    public final byte[] status;
    public final int[] concept;
    public final byte[] boundary;
    public final byte[] oob;
    public final List<Region> regions;
    public final Summary summary;
    public final String ruleVersion;
    public final List<String> sourceVersionIds;
    public final List<String> sourceFingerprints;
    public final double confidenceThreshold;

    private Analysis(int width, int height, byte[] status, int[] concept, byte[] boundary,
                     byte[] oob, List<Region> regions, Summary summary, String ruleVersion,
                     List<String> sourceVersionIds, List<String> sourceFingerprints,
                     double confidenceThreshold) {
        this.width = width;
        this.height = height;
        this.status = status;
        this.concept = concept;
        this.boundary = boundary;
        this.oob = oob;
        this.regions = regions;
        this.summary = summary;
        this.ruleVersion = ruleVersion;
        this.sourceVersionIds = sourceVersionIds;
        this.sourceFingerprints = sourceFingerprints;
        this.confidenceThreshold = confidenceThreshold;
    }

    public static final class Summary {
        public final long agreePixels;
        public final long disagreePixels;
        public final long uncoveredPixels;
        public final long boundaryPixels;
        public final long outOfBoundsPixels;

        public Summary(long a, long d, long u, long b, long o) {
            this.agreePixels = a;
            this.disagreePixels = d;
            this.uncoveredPixels = u;
            this.boundaryPixels = b;
            this.outOfBoundsPixels = o;
        }
    }

    public static final class Region {
        public final String id;
        public final byte kind;
        public final int pixelCount;
        public final long boundaryPixels;
        public final long outOfBoundsPixels;
        public final List<Poly> rings;
        public final String boundaryHash;
        public final int conceptKey;
        public final List<Integer> disagreementConcepts;
        public final List<String> witnessSourceVersions;

        Region(String id, byte kind, int pixelCount, long boundaryPixels, long outOfBoundsPixels,
               List<Poly> rings, String boundaryHash, int conceptKey,
               List<Integer> disagreementConcepts, List<String> witnessSourceVersions) {
            this.id = id;
            this.kind = kind;
            this.pixelCount = pixelCount;
            this.boundaryPixels = boundaryPixels;
            this.outOfBoundsPixels = outOfBoundsPixels;
            this.rings = rings;
            this.boundaryHash = boundaryHash;
            this.conceptKey = conceptKey;
            this.disagreementConcepts = disagreementConcepts;
            this.witnessSourceVersions = witnessSourceVersions;
        }

        public String kindName() {
            return kind == AGREE ? "AGREE" : kind == DISAGREE ? "DISAGREE" : "UNCOVERED";
        }
    }

    /** Build the concept resolver from confirmed mapping groups and raw refs. */
    public static ConceptResolver resolver(Model model) {
        Map<String, Integer> conceptByRef = new LinkedHashMap<>();
        List<List<Model.CategoryRef>> groups = new ArrayList<>();
        for (Model.MappingGroup g : model.mappingGroups.values()) {
            if (!"CONFIRMED".equals(g.status)) {
                continue;
            }
            int conceptId = groups.size();
            groups.add(List.copyOf(g.refs));
            for (Model.CategoryRef ref : g.refs) {
                conceptByRef.put(ref.key(), conceptId);
            }
        }
        return new ConceptResolver(conceptByRef, groups);
    }

    public static final class ConceptResolver {
        private final Map<String, Integer> byRef;
        private final List<List<Model.CategoryRef>> groups;

        ConceptResolver(Map<String, Integer> byRef, List<List<Model.CategoryRef>> groups) {
            this.byRef = byRef;
            this.groups = groups;
        }

        /** Returns -1 if the ref has no confirmed mapping. */
        public int resolve(Model.CategoryRef ref) {
            Integer id = byRef.get(ref.key());
            return id == null ? -1 : id;
        }

        public int groupCount() {
            return groups.size();
        }
    }

    public static Analysis compute(Model model, Map<String, Mask> alignedMasks) {
        if (model.image == null) {
            throw new IllegalStateException("image not created");
        }
        int width = model.image.width;
        int height = model.image.height;
        int n = width * height;
        List<Model.SourceVersion> versions = model.activeVersions();
        ConceptResolver resolver = resolver(model);

        byte[] status = new byte[n];
        int[] concept = new int[n];
        byte[] boundary = new byte[n];
        byte[] oob = new byte[n];

        List<String> versionIds = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();

        for (Model.SourceVersion v : versions) {
            Mask mask = alignedMasks.get(v.id);
            if (mask == null) {
                throw new IllegalStateException("derived mask missing for source version " + v.id);
            }
            if (mask.width() != width || mask.height() != height) {
                throw new IllegalStateException("aligned mask " + v.id + " has wrong dimensions");
            }
            versionIds.add(v.id);
            fingerprints.add(v.derivedFingerprint != null
                    ? v.derivedFingerprint : v.rawFingerprint);
        }

        long[] counts = new long[3];
        long boundaryCount = 0;
        long oobCount = 0;

        for (int i = 0; i < n; i++) {
            int first = -1;
            int second = -1;
            boolean any = false;
            boolean anyOob = false;
            for (Model.SourceVersion v : versions) {
                Mask mask = alignedMasks.get(v.id);
                int label = mask.label(i);
                if (label == -1) {
                    anyOob = true;
                    continue;
                }
                if (label == 0) {
                    continue;
                }
                if (mask.confidence(i) < model.confidenceThreshold) {
                    continue;
                }
                Model.CategoryRef ref = new Model.CategoryRef(v.vocabularyId,
                        v.vocabularyVersion, Integer.toString(label));
                int c = resolver.resolve(ref);
                if (c < 0) {
                    // Unmapped ref: treated as its own isolated concept so that
                    // same-name/different-vocab refs never silently agree.
                    c = encodeUnmapped(ref);
                }
                if (!any) {
                    first = c;
                    any = true;
                } else if (c != first && second < 0) {
                    second = c;
                }
            }
            if (anyOob) {
                oob[i] = 1;
                oobCount++;
                boundary[i] = 1;
                boundaryCount++;
            }
            if (!any) {
                status[i] = UNCOVERED;
                counts[2]++;
                concept[i] = -1;
            } else if (second < 0) {
                status[i] = AGREE;
                counts[0]++;
                concept[i] = first;
            } else {
                status[i] = DISAGREE;
                counts[1]++;
                concept[i] = first;
            }
            boolean b = false;
            for (Model.SourceVersion v : versions) {
                if (alignedMasks.get(v.id).boundary(i)) {
                    b = true;
                    break;
                }
            }
            if (b) {
                boundary[i] = 1;
                if (!anyOob) {
                    boundaryCount++;
                }
            }
        }

        List<Region> regions = buildRegions(status, concept, boundary, oob, width, height,
                alignedMasks, versions);
        return new Analysis(width, height, status, concept, boundary, oob, regions,
                new Summary(counts[0], counts[1], counts[2], boundaryCount, oobCount),
                RULE_VERSION, versionIds, fingerprints, model.confidenceThreshold);
    }

    private static int encodeUnmapped(Model.CategoryRef ref) {
        int h = Hash.sha256Hex(ref.key()).hashCode();
        return Math.abs(h) | 0x40000000;
    }

    private static List<Region> buildRegions(byte[] status, int[] concept, byte[] boundary,
                                             byte[] oob, int width, int height,
                                             Map<String, Mask> masks,
                                             List<Model.SourceVersion> versions) {
        List<Region> regions = new ArrayList<>();
        boolean[] seen = new boolean[status.length];
        int[] stack = new int[status.length];
        int[] member = new int[status.length];
        int[] compLabels = new int[status.length];
        int regionIndex = 0;
        for (int start = 0; start < status.length; start++) {
            if (seen[start] || status[start] == UNCOVERED) {
                continue;
            }
            regionIndex++;
            byte kind = status[start];
            int regionConcept = concept[start];
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            int pixels = 0;
            long bcount = 0;
            long ocount = 0;
            Set<Integer> disagree = new java.util.HashSet<>();
            Set<String> witnesses = new java.util.LinkedHashSet<>();
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;
            while (top > 0) {
                int p = stack[--top];
                member[p] = 1;
                compLabels[p] = regionIndex;
                int x = p % width;
                int y = p / width;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                pixels++;
                if (boundary[p] != 0) bcount++;
                if (oob[p] != 0) ocount++;
                if (kind == DISAGREE) {
                    for (Model.SourceVersion v : versions) {
                        int label = masks.get(v.id).label(p);
                        if (label > 0) {
                            disagree.add(label);
                            witnesses.add(v.id);
                        }
                    }
                }
                int[] nb = neighbors(p, x, y, width, height);
                for (int q : nb) {
                    if (q >= 0 && !seen[q] && status[q] == kind
                            && (kind != AGREE || concept[q] == regionConcept)) {
                        seen[q] = true;
                        stack[top++] = q;
                    }
                }
            }
            List<Poly> rings = Contour.ofComponent(regionIndex, compLabels, width, height);
            String boundaryHash = regionBoundaryHash(rings, pixels);
            String id = "reg_" + regionIndex + "_" + Long.toHexString(boundaryHash.hashCode() & 0xffffffffL);
            regions.add(new Region(id, kind, pixels, bcount, ocount, rings, boundaryHash,
                    kind == AGREE ? regionConcept : -1,
                    new ArrayList<>(disagree), new ArrayList<>(witnesses)));
        }
        return regions;
    }

    private static int[] neighbors(int p, int x, int y, int width, int height) {
        int[] r = {-1, -1, -1, -1};
        if (x > 0) r[0] = p - 1;
        if (x + 1 < width) r[1] = p + 1;
        if (y > 0) r[2] = p - width;
        if (y + 1 < height) r[3] = p + width;
        return r;
    }

    /**
     * Deterministic boundary hash: canonical ring coordinates (integer
     * corners) + pixel count. Stable across re-import as long as geometry is.
     */
    public static String regionBoundaryHash(List<Poly> rings, long pixelCount) {
        StringBuilder sb = new StringBuilder();
        List<Poly> sorted = new ArrayList<>(rings);
        sorted.sort((a, b) -> Long.compare(
                ringKey(a), ringKey(b)));
        for (Poly ring : sorted) {
            for (int i = 0; i < ring.n(); i++) {
                sb.append(Math.round(ring.xs[i])).append(',')
                        .append(Math.round(ring.ys[i])).append(';');
            }
            sb.append('|');
        }
        sb.append(pixelCount);
        return Hash.sha256Hex(sb.toString());
    }

    private static long ringKey(Poly p) {
        long k = 0;
        for (int i = 0; i < p.n(); i++) {
            k = k * 31 + Double.hashCode(p.xs[i]);
            k = k * 31 + Double.hashCode(p.ys[i]);
        }
        return k;
    }
}
