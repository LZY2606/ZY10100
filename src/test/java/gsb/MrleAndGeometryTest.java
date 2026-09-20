package gsb;

import gsb.geo.Geometry;
import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MrleAndGeometryTest {

    @Test
    void mrleRoundTripsLabelsConfidenceAndBoundary() {
        Mask m = new Mask(7, 5);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                int i = y * 7 + x;
                m.labels()[i] = (x < 3 ? 1 : x > 4 ? 2 : 0);
                m.confidence()[i] = m.label(i) > 0 ? 0.5f + (i % 3) * 0.1f : 0f;
                if (x == 3 || x == 4) {
                    m.markBoundary(i);
                }
            }
        }
        byte[] encoded = MrleCodec.encode(m);
        assertTrue(encoded.length < m.size() * 9, "rle should be smaller than raw floats");
        Mask decoded = MrleCodec.decode(encoded);
        assertEquals(m.width(), decoded.width());
        assertEquals(m.height(), decoded.height());
        assertArrayEquals(m.labels(), decoded.labels());
        assertArrayEquals(m.confidence(), decoded.confidence(), 0.0001f);
        for (int i = 0; i < m.size(); i++) {
            assertEquals(m.boundary(i), decoded.boundary(i));
        }
    }

    @Test
    void oobLabelMinusOneSurvivesEncoding() {
        Mask m = new Mask(2, 2);
        m.labels()[0] = -1;
        m.labels()[1] = 1;
        m.labels()[3] = -1;
        Mask decoded = MrleCodec.decode(MrleCodec.encode(m));
        assertEquals(-1, decoded.label(0));
        assertEquals(1, decoded.label(1));
        assertEquals(-1, decoded.label(3));
    }

    @Test
    void rejectsCorruptMrle() {
        byte[] bad = "MRL1not-a-real-payload-............................".getBytes();
        assertThrows(IllegalArgumentException.class, () -> MrleCodec.decode(bad));
    }

    @Test
    void downscaleMarksFractionalBoundaryPixels() {
        Mask src = new Mask(8, 8);
        for (int i = 0; i < src.size(); i++) {
            src.labels()[i] = 1;
            src.confidence()[i] = 1f;
        }
        Geometry.ResampleResult r = Geometry.resample(src, 8, 8, 1.0, 2.0, 0, 4, 4);
        assertTrue(r.resampled);
        assertTrue(r.boundaryPixels > 0, "fractional seams must be marked");
        for (int i = 0; i < r.mask.size(); i++) {
            assertTrue(r.mask.boundary(i), "scale 0.5 maps every pixel to a fractional footprint");
        }
    }

    @Test
    void rotationNearestNeighbourPreservesValues() {
        Mask src = new Mask(2, 3);
        src.labels()[0] = 1;
        src.labels()[5] = 2;
        Geometry.ResampleResult r = Geometry.resample(src, 2, 3, 1, 1, 90, 3, 2);
        assertTrue(r.resampled);
        boolean has1 = false;
        boolean has2 = false;
        for (int i = 0; i < r.mask.size(); i++) {
            has1 |= r.mask.label(i) == 1;
            has2 |= r.mask.label(i) == 2;
        }
        assertTrue(has1 && has2);
        assertEquals(0, r.outOfBoundsPixels);
    }
}
