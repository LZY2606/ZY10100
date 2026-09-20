package gsb.geom;

import gsb.rle.RleCodec;

/**
 * Nearest-neighbour resampling of a label grid onto a different raster that covers the
 * identical physical rectangle. Every reference pixel whose nearest source pixel is a
 * 4-neighbour label-boundary pixel (or whose footprint straddles more than one source pixel)
 * is flagged as a resample edge, so downstream comparisons can quarantine interpolation
 * boundary artefacts instead of treating them as genuine disagreement.
 */
public final class Resampler {
    private Resampler() {}

    public static RleCodec.Grid resample(RleCodec.Grid src, Geometry srcGeom, Geometry refGeom) {
        if (srcGeom.width() != src.width() || srcGeom.height() != src.height()) {
            throw new IllegalArgumentException("source raster/geometry mismatch");
        }
        if (srcGeom.identicalGrid(refGeom)) {
            return new RleCodec.Grid(src.width(), src.height(),
                    src.labels().clone(), src.edgeFlags().clone());
        }
        int rw = refGeom.width();
        int rh = refGeom.height();
        int[] out = new int[rw * rh];
        byte[] edges = new byte[rw * rh];

        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                // Physical coordinate of the reference pixel centre.
                double px = refGeom.originX() + x * refGeom.spacingX();
                double py = refGeom.originY() + y * refGeom.spacingY();
                // Nearest source pixel index.
                int sx = (int) Math.round((px - srcGeom.originX()) / srcGeom.spacingX());
                int sy = (int) Math.round((py - srcGeom.originY()) / srcGeom.spacingY());
                int cx = clamp(sx, 0, srcGeom.width() - 1);
                int cy = clamp(sy, 0, srcGeom.height() - 1);
                out[y * rw + x] = src.labels()[cy * src.width() + cx];

                boolean edge = src.edgeFlags()[cy * src.width() + cx] != 0;
                // Boundary: any 4-neighbour of the nearest source pixel carries a different label.
                int center = src.labels()[cy * src.width() + cx];
                if (!edge) {
                    edge = neighborDiffers(src, cx, cy, center);
                }
                // Footprint straddles multiple source pixels when reference pixel is larger.
                if (!edge && refGeom.spacingX() > srcGeom.spacingX()
                        || refGeom.spacingY() > srcGeom.spacingY()) {
                    edge = footprintStraddles(src, srcGeom, px, py, refGeom, center);
                }
                // Off-grid rounding clamp means the physical point fell outside source coverage.
                if (sx != cx || sy != cy) {
                    edge = true;
                }
                edges[y * rw + x] = (byte) (edge ? 1 : 0);
            }
        }
        return new RleCodec.Grid(rw, rh, out, edges);
    }

    private static boolean neighborDiffers(RleCodec.Grid src, int cx, int cy, int center) {
        int w = src.width();
        int h = src.height();
        if (cx > 0 && src.labels()[cy * w + cx - 1] != center) {
            return true;
        }
        if (cx + 1 < w && src.labels()[cy * w + cx + 1] != center) {
            return true;
        }
        if (cy > 0 && src.labels()[(cy - 1) * w + cx] != center) {
            return true;
        }
        return cy + 1 < h && src.labels()[(cy + 1) * w + cx] != center;
    }

    private static boolean footprintStraddles(RleCodec.Grid src, Geometry srcGeom,
                                              double px, double py, Geometry refGeom, int center) {
        // Sample the four corners of the reference pixel footprint in source-index space.
        double halfRx = refGeom.spacingX() / (2.0 * srcGeom.spacingX());
        double halfRy = refGeom.spacingY() / (2.0 * srcGeom.spacingY());
        double fx = (px - srcGeom.originX()) / srcGeom.spacingX();
        double fy = (py - srcGeom.originY()) / srcGeom.spacingY();
        double[][] corners = {
                {fx - halfRx, fy - halfRy}, {fx + halfRx, fy - halfRy},
                {fx - halfRx, fy + halfRy}, {fx + halfRx, fy + halfRy}
        };
        for (double[] c : corners) {
            int ix = clamp((int) Math.floor(c[0] + 0.5), 0, srcGeom.width() - 1);
            int iy = clamp((int) Math.floor(c[1] + 0.5), 0, srcGeom.height() - 1);
            if (src.labels()[iy * src.width() + ix] != center) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
