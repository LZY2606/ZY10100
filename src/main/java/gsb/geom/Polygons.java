package gsb.geom;

import gsb.model.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-grid polygon operations using scan-line rasterisation into a byte coverage mask.
 * Geometry is computed on a fixed workspace grid (the canonical image dimensions) so that
 * "overlapping pixels" have an exact, reproducible meaning for conflict detection.
 */
public final class Polygons {
    private Polygons() {}

    /** Rasterise a polygon: output[p] = 1 for covered pixels (pixel-centre rule). */
    public static byte[] rasterize(Polygon poly, int width, int height) {
        byte[] mask = new byte[width * height];
        double minY = Math.max(0, Math.floor(poly.minY()));
        double maxY = Math.min(height - 1, Math.ceil(poly.maxY()));
        for (int y = (int) minY; y <= (int) maxY; y++) {
            double yc = y + 0.5;
            List<Double> crossings = new ArrayList<>();
            List<double[]> ring = poly.ring();
            int n = ring.size();
            for (int i = 0; i < n; i++) {
                double[] a = ring.get(i);
                double[] b = ring.get((i + 1) % n);
                double ay = a[1];
                double by = b[1];
                if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) {
                    double t = (yc - ay) / (by - ay);
                    crossings.add(a[0] + t * (b[0] - a[0]));
                }
            }
            crossings.sort(Double::compare);
            for (int k = 0; k + 1 < crossings.size(); k += 2) {
                int x0 = (int) Math.ceil(crossings.get(k) - 0.5);
                int x1 = (int) Math.floor(crossings.get(k + 1) - 0.5);
                x0 = Math.max(0, x0);
                x1 = Math.min(width - 1, x1);
                for (int x = x0; x <= x1; x++) {
                    mask[y * width + x] = 1;
                }
            }
        }
        return mask;
    }

    public static long areaPixels(Polygon poly, int width, int height) {
        byte[] m = rasterize(poly, width, height);
        long c = 0;
        for (byte b : m) {
            c += b;
        }
        return c;
    }

    /** Covered pixel count of an already rasterised mask. */
    public static long covered(byte[] mask) {
        long c = 0;
        for (byte b : mask) {
            c += b;
        }
        return c;
    }

    public static boolean intersects(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != 0 && b[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /** a AND b pixel mask. */
    public static byte[] intersection(byte[] a, byte[] b) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (byte) ((a[i] != 0 && b[i] != 0) ? 1 : 0);
        }
        return r;
    }

    /** a AND NOT b pixel mask. */
    public static byte[] subtract(byte[] a, byte[] b) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (byte) ((a[i] != 0 && b[i] == 0) ? 1 : 0);
        }
        return r;
    }

    /**
     * Trace a coverage mask back into simple rectilinear polygons (one per contiguous run-band),
     * used to return human-readable conflict polygons. Rectangles are sufficient to exactly
     * describe the pixel-level overlap and keep the output deterministic.
     */
    public static List<Polygon> maskToRectPolygons(byte[] mask, int width, int height) {
        List<Polygon> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                if (mask[y * width + x] != 0) {
                    int x0 = x;
                    while (x < width && mask[y * width + x] != 0) {
                        x++;
                    }
                    int x1 = x; // exclusive
                    // merge downward while the identical span stays covered
                    int y1 = y + 1;
                    while (y1 < height && spanFullyCovered(mask, width, y1, x0, x1)) {
                        y1++;
                    }
                    out.add(rect(x0, y, x1, y1));
                    y = y1 - 1;
                    break;
                }
                x++;
            }
        }
        return out;
    }

    private static boolean spanFullyCovered(byte[] mask, int width, int y, int x0, int x1) {
        for (int x = x0; x < x1; x++) {
            if (mask[y * width + x] == 0) {
                return false;
            }
        }
        return true;
    }

    private static Polygon rect(int x0, int y0, int x1Exclusive, int y1Exclusive) {
        // Pixel x0..x1-1 covered; polygon boundary at half-pixel around those centres.
        double left = x0 - 0.5;
        double top = y0 - 0.5;
        double right = x1Exclusive - 0.5;
        double bottom = y1Exclusive - 0.5;
        return new Polygon(List.of(
                new double[]{left, top},
                new double[]{right, top},
                new double[]{right, bottom},
                new double[]{left, bottom}));
    }
}
