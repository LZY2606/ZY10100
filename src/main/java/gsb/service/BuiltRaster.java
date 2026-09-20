package gsb.service;

/** Result of synthesising the derived consensus raster from sources and decision events. */
public record BuiltRaster(int width, int height, int[] labels, byte[] edges, long regionPixels,
                          String boundaryHash, long staleDecisionPixels) {
    public int pixelCount() {
        return width * height;
    }
}
