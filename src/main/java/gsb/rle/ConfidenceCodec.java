package gsb.rle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Per-pixel confidence grid stored quantised to 0..255 (2 decimal digits of precision is more
 * than enough for threshold comparisons and keeps evidence blobs compact).
 */
public final class ConfidenceCodec {
    private ConfidenceCodec() {}

    public static byte[] encode(double[] confidence) {
        if (confidence == null) {
            throw new IllegalArgumentException("confidence grid required");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(confidence.length + 4);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(confidence.length);
            for (double v : confidence) {
                if (Double.isNaN(v) || v < 0 || v > 1) {
                    throw new IllegalArgumentException("confidence must be within [0,1]");
                }
                int q = (int) Math.round(v * 255.0);
                out.writeByte(Math.max(0, Math.min(255, q)));
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("confidence encode failed", e);
        }
    }

    public static double[] decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int n = in.readInt();
            if (n < 0) {
                throw new IllegalArgumentException("corrupt confidence blob");
            }
            double[] out = new double[n];
            byte[] raw = in.readNBytes(n);
            if (raw.length != n) {
                throw new IllegalArgumentException("corrupt confidence blob: truncated");
            }
            for (int i = 0; i < n; i++) {
                out[i] = (raw[i] & 0xFF) / 255.0;
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt confidence blob", e);
        }
    }
}
