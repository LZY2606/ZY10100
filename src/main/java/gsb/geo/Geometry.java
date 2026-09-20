package gsb.geo;

import gsb.mask.Mask;

/**
 * Geometry validation and resampling from a source mask's native pixel
 * coordinate system into the target image pixel coordinate system.
 *
 * <p>The forward map is:
 * <pre>
 * target = R(orientation) * diag(spacingSource / spacingTarget) * source + offset
 * </pre>
 * where {@code R} is a multiple of 90 degrees. Resampling is nearest-neighbour
 * with explicit boundary-pixel and out-of-bounds marking; input masks are never
 * mutated.
 */
public final class Geometry {
    public static final double SPACING_TOLERANCE = 1e-6;

    private Geometry() {}

    /** Validate declared pixel spacing: positive, equal x/y to tolerance. */
    public static void checkSpacing(double spacingX, double spacingY) {
        if (!(spacingX > 0) || !(spacingY > 0)) {
            throw new IllegalArgumentException("pixel spacing must be positive");
        }
        if (Math.abs(spacingX - spacingY) > SPACING_TOLERANCE
                * Math.max(1.0, Math.max(spacingX, spacingY))) {
            throw new IllegalArgumentException(
                    "anisotropic pixel spacing unsupported: " + spacingX + " vs " + spacingY);
        }
    }

    /** Orientation as clockwise degrees; normalized to {0,90,180,270}. */
    public static int normalizeOrientation(int degrees) {
        int o = ((degrees % 360) + 360) % 360;
        if (o % 90 != 0) {
            throw new IllegalArgumentException("orientation must be a multiple of 90, got " + degrees);
        }
        return o;
    }

    public static final class ResampleResult {
        public final Mask mask;
        public final boolean resampled;
        public final long boundaryPixels;
        public final long outOfBoundsPixels;

        ResampleResult(Mask mask, boolean resampled, long boundaryPixels, long outOfBoundsPixels) {
            this.mask = mask;
            this.resampled = resampled;
            this.boundaryPixels = boundaryPixels;
            this.outOfBoundsPixels = outOfBoundsPixels;
        }
    }

    /**
     * Map {@code source} into a {@code targetW x targetH} frame.
     *
     * @param sourceW native mask width
     * @param sourceH native mask height
     * @param sourceSpacing native isotropic spacing
     * @param targetSpacing image isotropic spacing
     * @param orientation clockwise rotation of source into image frame
     */
    public static ResampleResult resample(Mask source, int sourceW, int sourceH,
                                          double sourceSpacing, double targetSpacing,
                                          int orientation, int targetW, int targetH) {
        checkSpacing(sourceSpacing, sourceSpacing);
        checkSpacing(targetSpacing, targetSpacing);
        int o = normalizeOrientation(orientation);
        double scale = sourceSpacing / targetSpacing;
        if (!(scale > 0) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("bad spacing ratio");
        }
        boolean rotated = o == 90 || o == 270;
        int frameW = rotated ? sourceH : sourceW;
        int frameH = rotated ? sourceW : sourceH;

        boolean needs = Math.abs(scale - 1.0) > SPACING_TOLERANCE || o != 0
                || frameW != targetW || frameH != targetH;

        Mask out = new Mask(targetW, targetH);
        long boundaryCount = 0;
        long oobCount = 0;

        if (!needs) {
            Mask direct = new Mask(targetW, targetH, source.labels().clone(),
                    source.confidence().clone(), source.boundaryFlags().clone());
            return new ResampleResult(direct, false, 0, 0);
        }

        boolean scaleBoundary = Math.abs(scale - 1.0) > SPACING_TOLERANCE;
        for (int ty = 0; ty < targetH; ty++) {
            for (int tx = 0; tx < targetW; tx++) {
                double fx = tx + 0.5;
                double fy = ty + 0.5;
                double ux = fx;
                double uy = fy;
                switch (o) {
                    case 0 -> {
                        ux = fx;
                        uy = fy;
                    }
                    case 90 -> {
                        // target is source rotated clockwise: (sx,sy) -> (sy, W-1-sx)
                        ux = frameW - 1 - fy;
                        uy = fx;
                    }
                    case 180 -> {
                        ux = frameW - 1 - fx;
                        uy = frameH - 1 - fy;
                    }
                    case 270 -> {
                        // (sx,sy) -> (H-1-sy, sx)
                        ux = fy;
                        uy = frameH - 1 - fx;
                    }
                    default -> throw new IllegalStateException();
                }
                double sxCenter = ux / scale;
                double syCenter = uy / scale;
                int sx = (int) Math.floor(sxCenter);
                int sy = (int) Math.floor(syCenter);
                int ti = ty * targetW + tx;
                if (sx < 0 || sy < 0 || sx >= sourceW || sy >= sourceH) {
                    out.labels()[ti] = -1;
                    out.markBoundary(ti);
                    oobCount++;
                    boundaryCount++;
                    continue;
                }
                int si = sy * sourceW + sx;
                out.labels()[ti] = source.label(si);
                out.confidence()[ti] = source.confidence(si);
                if (scaleBoundary) {
                    // Target pixel footprint in native coordinates: if it is
                    // not exactly one whole native pixel, the value is a
                    // resampling boundary artifact.
                    double sx0 = tx / scale;
                    double sx1 = (tx + 1) / scale;
                    double sy0 = ty / scale;
                    double sy1 = (ty + 1) / scale;
                    if (!isWholePixel(sx0, sx1) || !isWholePixel(sy0, sy1)) {
                        out.markBoundary(ti);
                        boundaryCount++;
                    }
                } else if (source.boundary(si)) {
                    out.markBoundary(ti);
                    boundaryCount++;
                }
            }
        }
        return new ResampleResult(out, true, boundaryCount, oobCount);
    }

    private static boolean isWholePixel(double lo, double hi) {
        double a = Math.round(lo);
        double b = Math.round(hi);
        return Math.abs(lo - a) < 1e-9 && Math.abs(hi - b) < 1e-9
                && Math.abs((b - a) - 1.0) < 1e-9;
    }

    /** Rotate a native-grid mask in place coordinate space by clockwise degrees. */
    public static Mask rotateNative(Mask m, int degrees) {
        int o = normalizeOrientation(degrees);
        if (o == 0) {
            return m.copy();
        }
        int w = m.width();
        int h = m.height();
        int nw = (o == 90 || o == 270) ? h : w;
        int nh = (o == 90 || o == 270) ? w : h;
        Mask out = new Mask(nw, nh);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx;
                int ny;
                switch (o) {
                    case 90 -> {
                        nx = h - 1 - y;
                        ny = x;
                    }
                    case 180 -> {
                        nx = w - 1 - x;
                        ny = h - 1 - y;
                    }
                    default -> {
                        nx = y;
                        ny = w - 1 - x;
                    }
                }
                int from = y * w + x;
                int to = ny * nw + nx;
                out.labels()[to] = m.label(from);
                out.confidence()[to] = m.confidence(from);
                if (m.boundary(from)) {
                    out.markBoundary(to);
                }
            }
        }
        return out;
    }
}
