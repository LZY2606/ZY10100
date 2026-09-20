package gsb.service;

import gsb.geom.Geometry;
import gsb.geom.Resampler;
import gsb.model.ImageRef;
import gsb.model.Source;
import gsb.model.VocabEntry;
import gsb.model.Workspace;
import gsb.rle.ConfidenceCodec;
import gsb.rle.RleCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates and ingests one mask source as immutable evidence. */
public final class IngestService {

    public IngestResult ingest(WorkspaceService svc, IngestRequest req) {
        Workspace ws = svc.workspace();
        ValidationException.Builder errors = ValidationException.builder("INGEST_REJECTED");

        validateFingerprint(ws, req.image(), errors);
        validateDimensions(req, errors);
        validateVocabulary(req, errors);
        validateConfidence(req, errors);
        validateRevision(ws, req, errors);
        if (!errors.isEmpty()) {
            throw errors.build();
        }

        Geometry canonical = ws.canonicalGeometry();
        Geometry geo = req.geometry();
        RleCodec.Grid grid = new RleCodec.Grid(req.width(), req.height(),
                req.labels(), new byte[req.width() * req.height()]);
        List<String> warnings = new ArrayList<>();
        boolean resampled = false;

        if (!geo.identicalGrid(canonical)) {
            List<String> geoProblems = geo.differencesAgainst(canonical);
            if (geo.identicalGrid(canonical)) {
                // no-op
            } else if (geo.samePhysicalExtent(canonical)) {
                if (!req.allowResample()) {
                    throw ValidationException.of("RESAMPLE_REQUIRED",
                            "raster differs from canonical but covers the same physical extent; "
                                    + "resubmit with allowResample=true. " + String.join("; ", geoProblems));
                }
                grid = Resampler.resample(grid, geo, canonical);
                resampled = true;
                long edgeCount = 0;
                for (byte b : grid.edgeFlags()) {
                    edgeCount += b;
                }
                warnings.add("resampled from " + req.width() + "x" + req.height()
                        + " onto canonical " + canonical.width() + "x" + canonical.height()
                        + "; " + edgeCount + " boundary pixels flagged");
            } else {
                errors.add("geometry incompatible with canonical grid (same width/height is not "
                        + "enough to align pixels): " + String.join("; ", geoProblems));
                throw errors.build();
            }
        }

        byte[] maskBlob = RleCodec.encode(canonical.width(), canonical.height(),
                grid.labels(), grid.edgeFlags());
        double[] conf = req.confidence() == null
                ? defaultConfidence(grid.pixelCount())
                : resampleConfidence(req, geo, canonical, grid.pixelCount());
        byte[] confBlob = ConfidenceCodec.encode(conf);

        String maskHash = svc.store().blobs().put(maskBlob);
        String confHash = svc.store().blobs().put(confBlob);
        double mean = mean(conf);

        int revisionNo = 1;
        if (req.revisionOf() != null) {
            revisionNo = ws.source(req.revisionOf()).revisionNo() + 1;
        } else {
            Source sameAlgorithm = ws.latestRevisionOfFamily(req.algorithm());
            if (sameAlgorithm != null) {
                revisionNo = sameAlgorithm.revisionNo() + 1;
            }
        }

        Source source = new Source(
                svc.newId(),
                req.algorithm(),
                revisionNo,
                req.revisionOf(),
                Instant.now(svc.clock()),
                req.image(),
                canonical,
                req.vocabulary(),
                maskHash,
                confHash,
                maskBlob.length,
                mean);
        svc.addSource(source);
        new MappingService().proposeFor(svc, source);
        return new IngestResult(source, resampled, warnings);
    }

    private void validateFingerprint(Workspace ws, ImageRef image, ValidationException.Builder errors) {
        if (!image.fingerprintSha256().equals(ws.image().fingerprintSha256())) {
            errors.add("image fingerprint mismatch: source claims "
                    + image.fingerprintSha256().substring(0, 12) + "... but workspace is bound to "
                    + ws.image().fingerprintSha256().substring(0, 12)
                    + "... — comparison forbidden even if width/height happen to match");
        }
    }

    private void validateDimensions(IngestRequest req, ValidationException.Builder errors) {
        if (req.width() != req.geometry().width() || req.height() != req.geometry().height()) {
            errors.add("declared raster " + req.width() + "x" + req.height()
                    + " disagrees with geometry " + req.geometry().width() + "x"
                    + req.geometry().height());
        }
        int expected = req.geometry().width() * req.geometry().height();
        if (req.labels() == null || req.labels().length != expected) {
            errors.add("label array length " + (req.labels() == null ? "null" : req.labels().length)
                    + " does not equal geometry pixels " + expected);
        }
        if (req.labels() != null) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int label : req.labels()) {
                min = Math.min(min, label);
                max = Math.max(max, label);
            }
            if (req.labels().length > 0 && min < 0) {
                errors.add("negative label id " + min + " present in mask");
            }
        }
    }

    private void validateVocabulary(IngestRequest req, ValidationException.Builder errors) {
        if (req.labels() == null) {
            return;
        }
        Set<Integer> declared = new HashSet<>();
        for (VocabEntry e : req.vocabulary().entries()) {
            declared.add(e.classId());
        }
        Set<Integer> used = new HashSet<>();
        int outOfBounds = 0;
        int firstBad = -1;
        for (int label : req.labels()) {
            if (label != 0) {
                used.add(label);
                if (!declared.contains(label)) {
                    outOfBounds++;
                    if (firstBad < 0) {
                        firstBad = label;
                    }
                }
            }
        }
        if (outOfBounds > 0) {
            errors.add("mask contains " + outOfBounds + " pixels with label id " + firstBad
                    + " that is absent from vocabulary version '" + req.vocabulary().vocabVersion()
                    + "' (out-of-bounds class)");
        }
    }

    private void validateConfidence(IngestRequest req, ValidationException.Builder errors) {
        if (req.confidence() == null) {
            return;
        }
        int expected = req.geometry().width() * req.geometry().height();
        if (req.confidence().length != expected) {
            errors.add("confidence array length " + req.confidence().length
                    + " does not equal geometry pixels " + expected);
            return;
        }
        for (double v : req.confidence()) {
            if (Double.isNaN(v) || v < 0 || v > 1) {
                errors.add("confidence values must be within [0,1]");
                break;
            }
        }
    }

    private void validateRevision(Workspace ws, IngestRequest req, ValidationException.Builder errors) {
        if (req.revisionOf() == null) {
            return;
        }
        Source parent = ws.source(req.revisionOf());
        if (parent == null) {
            errors.add("revisionOf references unknown source " + req.revisionOf());
            return;
        }
        if (!parent.algorithm().equals(req.algorithm())) {
            errors.add("algorithm update must keep the same algorithm name ('"
                    + req.algorithm() + "' vs parent '" + parent.algorithm() + "')");
        }
    }

    private double[] defaultConfidence(int n) {
        double[] c = new double[n];
        java.util.Arrays.fill(c, 1.0);
        return c;
    }

    private double[] resampleConfidence(IngestRequest req, Geometry srcGeom,
                                        Geometry canonical, int canonicalPixels) {
        // Confidence follows the same nearest-neighbour footprint as labels.
        double[] src = req.confidence();
        double[] out = new double[canonicalPixels];
        if (srcGeom.identicalGrid(canonical)) {
            System.arraycopy(src, 0, out, 0, Math.min(src.length, out.length));
            return out;
        }
        for (int y = 0; y < canonical.height(); y++) {
            for (int x = 0; x < canonical.width(); x++) {
                double px = canonical.originX() + x * canonical.spacingX();
                double py = canonical.originY() + y * canonical.spacingY();
                int sx = clamp((int) Math.round((px - srcGeom.originX()) / srcGeom.spacingX()),
                        0, srcGeom.width() - 1);
                int sy = clamp((int) Math.round((py - srcGeom.originY()) / srcGeom.spacingY()),
                        0, srcGeom.height() - 1);
                out[y * canonical.width() + x] = src[sy * srcGeom.width() + sx];
            }
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

}
