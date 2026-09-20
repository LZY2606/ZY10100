package gsb.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marching-squares outlines for sets of pixels. Cell edges separating an
 * "on" cell from an "off" cell are collected as directed segments and joined
 * into closed linear rings. Pixel polygons use integer corner coordinates.
 */
public final class Contour {
    private Contour() {}

    public static List<Poly> ofComponent(int componentId, int[] componentLabels,
                                         int width, int height) {
        int n = width * height;
        boolean[] on = new boolean[n];
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int i = 0; i < n; i++) {
            if (componentLabels[i] == componentId) {
                on[i] = true;
                int x = i % width;
                int y = i / width;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) {
            return List.of();
        }
        Map<Long, List<long[]>> outEdges = new HashMap<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!on[y * width + x]) {
                    continue;
                }
                // top edge: neighbor above off
                if (y == 0 || !on[(y - 1) * width + x]) {
                    add(outEdges, x, y, x + 1, y);
                }
                // right edge
                if (x + 1 == width || !on[y * width + x + 1]) {
                    add(outEdges, x + 1, y, x + 1, y + 1);
                }
                // bottom edge
                if (y + 1 == height || !on[(y + 1) * width + x]) {
                    add(outEdges, x + 1, y + 1, x, y + 1);
                }
                // left edge
                if (x == 0 || !on[y * width + x - 1]) {
                    add(outEdges, x, y + 1, x, y);
                }
            }
        }
        return join(outEdges);
    }

    private static void add(Map<Long, List<long[]>> outEdges, int x1, int y1, int x2, int y2) {
        outEdges.computeIfAbsent(key(x1, y1), k -> new ArrayList<>())
                .add(new long[] {x1, y1, x2, y2});
    }

    private static List<Poly> join(Map<Long, List<long[]>> outEdges) {
        List<Poly> polys = new ArrayList<>();
        while (!outEdges.isEmpty()) {
            Long startKey = outEdges.keySet().iterator().next();
            long[] first = take(outEdges, startKey);
            List<Long> xs = new ArrayList<>();
            List<Long> ys = new ArrayList<>();
            xs.add(first[0]);
            ys.add(first[1]);
            xs.add(first[2]);
            ys.add(first[3]);
            long cursorX = first[2];
            long cursorY = first[3];
            while (!(cursorX == first[0] && cursorY == first[1])) {
                long[] edge = take(outEdges, key((int) cursorX, (int) cursorY));
                if (edge == null) {
                    break;
                }
                xs.add(edge[2]);
                ys.add(edge[3]);
                cursorX = edge[2];
                cursorY = edge[3];
            }
            if (xs.size() >= 4
                    && xs.get(0).equals(xs.get(xs.size() - 1))
                    && ys.get(0).equals(ys.get(ys.size() - 1))) {
                double[] px = new double[xs.size() - 1];
                double[] py = new double[ys.size() - 1];
                for (int i = 0; i < px.length; i++) {
                    px[i] = xs.get(i);
                    py[i] = ys.get(i);
                }
                polys.add(new Poly(px, py).ccw());
            }
        }
        return polys;
    }

    private static long[] take(Map<Long, List<long[]>> edges, Long key) {
        List<long[]> list = edges.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        long[] e = list.remove(list.size() - 1);
        if (list.isEmpty()) {
            edges.remove(key);
        }
        return e;
    }

    private static long key(int x, int y) {
        return ((long) (x + 32768) << 16) | (y + 32768);
    }
}
