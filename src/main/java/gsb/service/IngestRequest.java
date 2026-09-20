package gsb.service;

import gsb.geom.Geometry;
import gsb.model.ImageRef;
import gsb.model.Vocabulary;

/**
 * One mask submission. {@code labels} is a row-major flat array, length width*height;
 * label 0 = background. {@code confidence} may be null (treated as 1.0 everywhere).
 */
public record IngestRequest(
        String algorithm,
        String revisionOf,
        ImageRef image,
        Geometry geometry,
        Vocabulary vocabulary,
        int width,
        int height,
        int[] labels,
        double[] confidence,
        boolean allowResample) {
}
