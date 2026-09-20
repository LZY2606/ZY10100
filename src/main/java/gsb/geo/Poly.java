package gsb.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable 2D polygon ring in pixel coordinates. */
public final class Poly {
    public final double[] xs;
    public final double[] ys;

    public Poly(double[] xs, double[] ys) {
        if (xs.length != ys.length || xs.length < 3) {
            throw new IllegalArgumentException("polygon needs >= 3 vertices");
        }
        this.xs = xs;
        this.ys = ys;
    }

    public int n() {
        return xs.length;
    }

    public double signedArea2() {
        double a = 0;
        for (int i = 0; i < xs.length; i++) {
            int j = (i + 1) % xs.length;
            a += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return a * 0.5;
    }

    public Poly ccw() {
        return signedArea2() < 0 ? reversed() : this;
    }

    public Poly reversed() {
        double[] rx = new double[xs.length];
        double[] ry = new double[ys.length];
        for (int i = 0; i < xs.length; i++) {
            rx[i] = xs[xs.length - 1 - i];
            ry[i] = ys[ys.length - 1 - i];
        }
        return new Poly(rx, ry);
    }

    public List<List<Double>> toPoints() {
        List<List<Double>> pts = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            pts.add(List.of(round(xs[i]), round(ys[i])));
        }
        return pts;
    }

    private static double round(double v) {
        return Math.rint(v * 1e6) / 1e6;
    }
}
