package gsb.geo;

import java.util.ArrayList;
import java.util.List;

/** 4-connected components on a pixel mask. */
public final class Components {
    private Components() {}

    public static final class Component {
        public final int id;
        public final int pixelCount;
        public final int minX;
        public final int minY;
        public final int maxX;
        public final int maxY;

        Component(int id, int pixelCount, int minX, int minY, int maxX, int maxY) {
            this.id = id;
            this.pixelCount = pixelCount;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    /**
     * Label every true cell in {@code cells} with a 1-based component id.
     *
     * @return labels array plus component list (index 0 unused)
     */
    public static Result label(boolean[] cells, int width, int height) {
        int n = cells.length;
        int[] labels = new int[n];
        int[] stack = new int[n];
        int componentCount = 0;
        List<Component> components = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (!cells[start] || labels[start] != 0) {
                continue;
            }
            componentCount++;
            int top = 0;
            stack[top++] = start;
            labels[start] = componentCount;
            int count = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            while (top > 0) {
                int p = stack[--top];
                int x = p % width;
                int y = p / width;
                count++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (x > 0) {
                    int q = p - 1;
                    if (cells[q] && labels[q] == 0) {
                        labels[q] = componentCount;
                        stack[top++] = q;
                    }
                }
                if (x + 1 < width) {
                    int q = p + 1;
                    if (cells[q] && labels[q] == 0) {
                        labels[q] = componentCount;
                        stack[top++] = q;
                    }
                }
                if (y > 0) {
                    int q = p - width;
                    if (cells[q] && labels[q] == 0) {
                        labels[q] = componentCount;
                        stack[top++] = q;
                    }
                }
                if (y + 1 < height) {
                    int q = p + width;
                    if (cells[q] && labels[q] == 0) {
                        labels[q] = componentCount;
                        stack[top++] = q;
                    }
                }
            }
            components.add(new Component(componentCount, count, minX, minY, maxX, maxY));
        }
        return new Result(labels, components);
    }

    public static final class Result {
        public final int[] labels;
        public final List<Component> components;

        Result(int[] labels, List<Component> components) {
            this.labels = labels;
            this.components = components;
        }
    }
}
