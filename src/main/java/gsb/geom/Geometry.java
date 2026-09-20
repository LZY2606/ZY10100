package gsb.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable geometric reference of a pixel grid.
 *
 * @param width       columns (pixels)
 * @param height      rows (pixels)
 * @param spacingX    physical pixel spacing along a column step (mm per pixel, width direction)
 * @param spacingY    physical pixel spacing along a row step (mm per pixel, height direction)
 * @param orientation two-vector orientation tag, e.g. "L|A" (row|column anatomical direction)
 * @param originX     physical X of pixel (0,0) centre, mm
 * @param originY     physical Y of pixel (0,0) centre, mm
 */
public record Geometry(int width, int height, double spacingX, double spacingY,
                       String orientation, double originX, double originY) {

    /** Relative tolerance for pixel spacing comparison. */
    public static final double SPACING_REL_TOLERANCE = 1.0e-6;

    public Geometry {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("geometry dimensions must be positive");
        }
        if (spacingX <= 0 || spacingY <= 0 || Double.isNaN(spacingX) || Double.isNaN(spacingY)) {
            throw new IllegalArgumentException("pixel spacing must be positive");
        }
        if (orientation == null || orientation.isBlank()) {
            throw new IllegalArgumentException("orientation is required");
        }
        orientation = orientation.toUpperCase(Locale.ROOT).trim();
    }

    /** Physical extent covered by the pixel centres grid, mm. */
    public double extentX() {
        return (width - 1) * spacingX;
    }

    public double extentY() {
        return (height - 1) * spacingY;
    }

    public long pixelCount() {
        return (long) width * height;
    }

    /**
     * Compare this geometry against a reference geometry. Matching width/height is never
     * sufficient on its own: callers MUST additionally compare image fingerprints.
     *
     * @return human-readable mismatch reasons; empty list means fully identical.
     */
    public List<String> differencesAgainst(Geometry ref) {
        List<String> problems = new ArrayList<>();
        if (width != ref.width || height != ref.height) {
            problems.add(String.format(Locale.ROOT,
                    "size mismatch: %dx%d vs reference %dx%d",
                    width, height, ref.width, ref.height));
        }
        if (!orientation.equals(ref.orientation)) {
            problems.add(String.format(Locale.ROOT,
                    "orientation mismatch: '%s' vs reference '%s'", orientation, ref.orientation));
        }
        if (!relClose(spacingX, ref.spacingX) || !relClose(spacingY, ref.spacingY)) {
            problems.add(String.format(Locale.ROOT,
                    "pixel spacing mismatch: %.9gx%.9g vs reference %.9gx%.9g mm",
                    spacingX, spacingY, ref.spacingX, ref.spacingY));
        }
        if (relClose(spacingX, ref.spacingX) && relClose(spacingY, ref.spacingY)) {
            double dx = Math.abs(originX - ref.originX);
            double dy = Math.abs(originY - ref.originY);
            if (dx > SPACING_REL_TOLERANCE * Math.max(1.0, spacingX)
                    || dy > SPACING_REL_TOLERANCE * Math.max(1.0, spacingY)) {
                problems.add(String.format(Locale.ROOT,
                        "origin mismatch: (%.9g,%.9g) vs reference (%.9g,%.9g) mm",
                        originX, originY, ref.originX, ref.originY));
            }
        }
        return problems;
    }

    /**
     * A grid is resamplable onto the reference when it describes the same physical rectangle
     * (same orientation + extent within tolerance) regardless of its raster resolution.
     */
    public boolean samePhysicalExtent(Geometry ref) {
        if (!orientation.equals(ref.orientation)) {
            return false;
        }
        // Two rasters describe the same physical image when their full pixel footprints span
        // the same rectangle. Footprint width = (N-1)*spacing + average pixel width, i.e. the
        // difference between centre-span extents must not exceed half a pixel on either side.
        double tolX = 0.5 * (spacingX + ref.spacingX);
        double tolY = 0.5 * (spacingY + ref.spacingY);
        return Math.abs(extentX() - ref.extentX()) <= tolX
                && Math.abs(extentY() - ref.extentY()) <= tolY
                && Math.abs(originX - ref.originX) <= tolX
                && Math.abs(originY - ref.originY) <= tolY;
    }

    public boolean identicalGrid(Geometry ref) {
        return width == ref.width && height == ref.height
                && relClose(spacingX, ref.spacingX) && relClose(spacingY, ref.spacingY)
                && orientation.equals(ref.orientation)
                && Math.abs(originX - ref.originX) <= 1.0e-9
                && Math.abs(originY - ref.originY) <= 1.0e-9;
    }

    private static boolean relClose(double a, double b) {
        return Math.abs(a - b) <= SPACING_REL_TOLERANCE * Math.max(Math.abs(a), Math.abs(b));
    }
}
