package gsb;

import gsb.geom.Geometry;
import gsb.model.ImageRef;
import gsb.model.VocabEntry;
import gsb.model.Vocabulary;

import java.util.List;

/** Shared tiny 8x6 fixtures so tests stay fast and pixel-exact. */
public final class DomainFixtures {
    private DomainFixtures() {}

    public static final String FINGERPRINT_A = "a".repeat(64);
    public static final String FINGERPRINT_B = "b".repeat(64);

    public static Geometry geom() {
        return new Geometry(8, 6, 0.5, 0.5, "L|A", 0, 0);
    }

    public static ImageRef imageA() {
        return new ImageRef("img-1", FINGERPRINT_A);
    }

    public static ImageRef imageB() {
        return new ImageRef("img-other", FINGERPRINT_B);
    }

    public static Vocabulary vocab(String version) {
        return new Vocabulary(version, List.of(
                new VocabEntry(1, "liver", "#cc0000"),
                new VocabEntry(2, "tumor", "#00cc00")));
    }

    public static Vocabulary vocabV1() {
        return vocab("vocab-1");
    }

    public static Vocabulary vocabV2() {
        return vocab("vocab-2");
    }

    /** Label grid: rows of 8 for a 8x6 image. */
    public static int[] labels(String... rows) {
        int[] out = new int[rows.length * rows[0].length()];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                out[y * rows[0].length() + x] = rows[y].charAt(x) - '0';
            }
        }
        return out;
    }

    public static double[] confidenceAll(int width, int height, double value) {
        double[] c = new double[width * height];
        java.util.Arrays.fill(c, value);
        return c;
    }
}
