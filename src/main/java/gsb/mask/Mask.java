package gsb.mask;

import java.util.Arrays;

/**
 * Pixel-aligned label grid for one source version in the *target* image
 * pixel coordinate system.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code label == 0}: source does not cover that pixel (background / no claim).</li>
 *   <li>{@code label > 0}: id of a class in the source vocabulary.</li>
 *   <li>{@code label == -1}: OUT-OF-BOUNDS claim generated while resampling an
 *       aligned grid whose source footprint extends past the image frame; such
 *       pixels block publication.</li>
 *   <li>{@code boundary[i]} marks pixels whose value is a resampling artifact
 *       (boundary pixels introduced by resampling).</li>
 * </ul>
 */
public final class Mask {
    private final int width;
    private final int height;
    private final int[] labels;
    private final float[] confidence;
    private final byte[] boundary;
    private final int maxLabel;

    public Mask(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("mask dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        int n = (long) width * height > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : width * height;
        if ((long) width * height > n) {
            throw new IllegalArgumentException("mask too large");
        }
        this.labels = new int[n];
        this.confidence = new float[n];
        this.boundary = new byte[n];
        this.maxLabel = 0;
    }

    public Mask(int width, int height, int[] labels, float[] confidence, byte[] boundary) {
        int n = width * height;
        if (labels.length != n || (confidence != null && confidence.length != n)
                || (boundary != null && boundary.length != n)) {
            throw new IllegalArgumentException("buffer length mismatch");
        }
        this.width = width;
        this.height = height;
        this.labels = labels;
        this.confidence = confidence == null ? new float[n] : confidence;
        this.boundary = boundary == null ? new byte[n] : boundary;
        int max = 0;
        for (int l : labels) {
            if (l > max) {
                max = l;
            }
        }
        this.maxLabel = max;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int size() {
        return labels.length;
    }

    public int idx(int x, int y) {
        return y * width + x;
    }

    public boolean inFrame(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public int label(int i) {
        return labels[i];
    }

    public int labelAt(int x, int y) {
        return labels[idx(x, y)];
    }

    public void setLabelAt(int x, int y, int label) {
        labels[idx(x, y)] = label;
    }

    public float confidence(int i) {
        return confidence[i];
    }

    public float confidenceAt(int x, int y) {
        return confidence[idx(x, y)];
    }

    public void setConfidenceAt(int x, int y, float c) {
        confidence[idx(x, y)] = c;
    }

    public boolean boundary(int i) {
        return boundary[i] != 0;
    }

    public void markBoundary(int i) {
        boundary[i] = 1;
    }

    public int[] labels() {
        return labels;
    }

    public float[] confidence() {
        return confidence;
    }

    public byte[] boundaryFlags() {
        return boundary;
    }

    public int maxLabel() {
        return maxLabel;
    }

    public long countLabel(int target) {
        long c = 0;
        for (int l : labels) {
            if (l == target) {
                c++;
            }
        }
        return c;
    }

    public long countBoundary() {
        long c = 0;
        for (byte b : boundary) {
            if (b != 0) {
                c++;
            }
        }
        return c;
    }

    public Mask copy() {
        return new Mask(width, height, Arrays.copyOf(labels, labels.length),
                Arrays.copyOf(confidence, confidence.length),
                Arrays.copyOf(boundary, boundary.length));
    }
}
