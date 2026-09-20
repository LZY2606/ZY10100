package gsb.model;

import java.util.List;

/**
 * Simple axis-aligned polygon in source pixel coordinates. Vertices are [x,y] pairs; the ring
 * may be concave. Stored as coordinates but rasterised for conflict/coverage computation.
 */
public record Polygon(List<double[]> ring) {
    public Polygon {
        if (ring == null || ring.size() < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 vertices");
        }
        ring = List.copyOf(ring);
        for (double[] p : ring) {
            if (p.length != 2) {
                throw new IllegalArgumentException("polygon vertices must be [x,y]");
            }
        }
    }

    public double minX() {
        double m = Double.MAX_VALUE;
        for (double[] p : ring) {
            m = Math.min(m, p[0]);
        }
        return m;
    }

    public double minY() {
        double m = Double.MAX_VALUE;
        for (double[] p : ring) {
            m = Math.min(m, p[1]);
        }
        return m;
    }

    public double maxX() {
        double m = -Double.MAX_VALUE;
        for (double[] p : ring) {
            m = Math.max(m, p[0]);
        }
        return m;
    }

    public double maxY() {
        double m = -Double.MAX_VALUE;
        for (double[] p : ring) {
            m = Math.max(m, p[1]);
        }
        return m;
    }
}
