package gsb.geo;

import java.util.List;

/** Conversion between polygons (pixel-corner coordinates) and pixel sets. */
public final class RasterOps {
    private RasterOps() {}

    /**
     * Fill pixels whose closed pixel-cell area intersects the polygon interior
     * by more than the given ratio (0 = any overlap, 1 = full containment).
     * Uses per-scanline corner-crossing tests on the pixel cell rectangle.
     */
    public static boolean[] rasterize(List<List<Double>> polygon, int width, int height) {
        double[] xs = new double[polygon.size()];
        double[] ys = new double[polygon.size()];
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            xs[i] = polygon.get(i).get(0);
            ys[i] = polygon.get(i).get(1);
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        boolean[] cells = new boolean[width * height];
        int x0 = Math.max(0, (int) Math.floor(minX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int x1 = Math.min(width - 1, (int) Math.floor(maxX));
        int y1 = Math.min(height - 1, (int) Math.floor(maxY));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double area = overlapArea(xs, ys, x, y);
                if (area > 1e-9) {
                    cells[y * width + x] = true;
                }
            }
        }
        return cells;
    }

    /** Fraction [0,1] of the unit pixel cell covered by the polygon. */
    static double overlapArea(double[] xs, double[] ys, int px, int py) {
        int inside = 0;
        for (int cy = 0; cy <= 1; cy++) {
            for (int cx = 0; cx <= 1; cx++) {
                if (pointInPoly(xs, ys, px + cx, py + cy)) {
                    inside++;
                }
            }
        }
        if (inside == 4) {
            return 1.0;
        }
        if (inside == 0) {
            boolean hits = false;
            for (int k = 0; k < xs.length; k++) {
                int j = (k + 1) % xs.length;
                if (segmentHitsCell(xs[k], ys[k], xs[j], ys[j], px, py)) {
                    hits = true;
                    break;
                }
            }
            return hits ? 0.01 : 0.0;
        }
        return inside / 4.0;
    }

    public static boolean pointInPoly(double[] xs, double[] ys, double px, double py) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double xi = xs[i];
            double yi = ys[i];
            double xj = xs[j];
            double yj = ys[j];
            boolean intersect = ((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean segmentHitsCell(double x1, double y1, double x2, double y2,
                                           int px, int py) {
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);
        return maxX >= px && minX <= px + 1 && maxY >= py && minY <= py + 1;
    }

    /** True where either pixel set is true. */
    public static boolean[] union(boolean[] a, boolean[] b) {
        boolean[] out = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] || b[i];
        }
        return out;
    }

    /** True where both pixel sets are true. */
    public static boolean[] intersect(boolean[] a, boolean[] b) {
        boolean[] out = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] && b[i];
        }
        return out;
    }

    public static boolean[] subtract(boolean[] a, boolean[] b) {
        boolean[] out = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] && !b[i];
        }
        return out;
    }

    public static long count(boolean[] a) {
        long c = 0;
        for (boolean b : a) {
            if (b) {
                c++;
            }
        }
        return c;
    }
}
