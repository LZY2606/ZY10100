package gsb;

import gsb.rle.ConfidenceCodec;
import gsb.rle.RleCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RleCodecTest {

    @Test
    void roundTripsLabelsAndEdgeFlags() {
        int w = 4;
        int h = 3;
        int[] labels = {
                0, 0, 1, 1,
                0, 2, 2, 1,
                0, 0, 0, 0
        };
        byte[] edges = new byte[w * h];
        edges[2] = 1;
        edges[7] = 1;

        byte[] payload = RleCodec.encode(w, h, labels, edges);
        RleCodec.Grid grid = RleCodec.decode(payload);

        assertThat(grid.labels()).containsExactly(labels);
        assertThat(grid.edgeFlags()).containsExactly(edges);
        // This tiny grid has per-run overhead; assert the binary structure round trips and that a
        // large flat image compresses strongly (checked separately below).
        int bigW = 1024;
        int[] big = new int[bigW * 4];
        java.util.Arrays.fill(big, 7);
        byte[] bigPayload = RleCodec.encode(bigW, 4, big, new byte[big.length]);
        assertThat(bigPayload.length).isLessThan(big.length * 4 / 100);
    }

    @Test
    void rejectsCorruptRunsThatDoNotCoverGrid() throws Exception {
        // Valid header but hand-crafted short payload.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bos)) {
            out.writeInt(4);
            out.writeInt(4);
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(2); // only 2 of 16 pixels
            out.writeInt(2); // bitmap bytes
            out.write(new byte[2]);
        }
        assertThatThrownBy(() -> RleCodec.decode(bos.toByteArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs cover");
    }

    @Test
    void confidenceRoundTripsWithinQuantization() {
        double[] c = {0.0, 0.5, 1.0, 0.73};
        double[] back = ConfidenceCodec.decode(ConfidenceCodec.encode(c));
        assertThat(back).hasSize(4);
        assertThat(back[0]).isEqualTo(0.0);
        assertThat(back[2]).isEqualTo(1.0);
        assertThat(back[1]).isBetween(0.49, 0.51);
        assertThat(back[3]).isBetween(0.72, 0.74);
    }
}
