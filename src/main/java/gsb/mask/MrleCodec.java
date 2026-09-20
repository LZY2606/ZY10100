package gsb.mask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * MRLE1 compressed mask codec (native, dependency-free).
 *
 * <p>Container (all multi-byte values big-endian):
 * <pre>
 * magic   = "MRL1" (4 bytes)
 * width   int32
 * height  int32
 * flags   int32  bit0 = confidence block present, bit1 = boundary block present
 * payload = DEFLATE stream of:
 *   labelRuns: uvarint(label+1), uvarint(run); terminator uvarint(0)
 *   if bit0: width*height float32 confidence values
 *   if bit1: packed boundary bits, one bit per pixel, row-major, MSB first,
 *            padded to a whole byte
 * </pre>
 * Stored label token {@code label+2}: 0 is the end sentinel, so -1 (OOB), 0 and
 * positive class ids are both representable.
 */
public final class MrleCodec {
    public static final String FORMAT = "mrle1";
    private static final byte[] MAGIC = {'M', 'R', 'L', '1'};

    private MrleCodec() {}

    public static byte[] encode(Mask mask) {
        int n = mask.size();
        byte[] packedBoundary = packBoundary(mask);
        boolean hasConf = false;
        for (float c : mask.confidence()) {
            if (c != 0f) {
                hasConf = true;
                break;
            }
        }
        int flags = (hasConf ? 1 : 0) | (packedBoundary != null ? 2 : 0);
        try {
            ByteArrayOutputStream container = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(container);
            out.write(MAGIC);
            out.writeInt(mask.width());
            out.writeInt(mask.height());
            out.writeInt(flags);

            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DataOutputStream payload = new DataOutputStream(
                    new DeflaterOutputStream(container, deflater))) {
                int[] labels = mask.labels();
                int runStart = 0;
                while (runStart < n) {
                    int value = labels[runStart];
                    int end = runStart + 1;
                    while (end < n && labels[end] == value) {
                        end++;
                    }
                    writeUvarint(payload, value + 2L);
                    writeUvarint(payload, (long) (end - runStart));
                    runStart = end;
                }
                writeUvarint(payload, 0L);

                if (hasConf) {
                    ByteBuffer bb = ByteBuffer.allocate(n * 4).order(ByteOrder.BIG_ENDIAN);
                    bb.asFloatBuffer().put(mask.confidence());
                    payload.write(bb.array());
                }
                if (packedBoundary != null) {
                    payload.write(packedBoundary);
                }
            }
            return container.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("mrle encode failed", e);
        }
    }

    public static Mask decode(byte[] data) {
        if (data == null || data.length < 16) {
            throw new IllegalArgumentException("mrle: truncated file");
        }
        for (int i = 0; i < 4; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IllegalArgumentException("mrle: bad magic (expected MRL1)");
            }
        }
        int width = readInt(data, 4);
        int height = readInt(data, 8);
        int flags = readInt(data, 12);
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mrle: invalid dimensions " + width + "x" + height);
        }
        int n = width * height;
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(data, 16, data.length - 16),
                        inflater))) {
            int[] labels = new int[n];
            int filled = 0;
            while (true) {
                long token = readUvarint(in);
                if (token == 0) {
                    break;
                }
                int value = (int) token - 2;
                long run = readUvarint(in);
                if (run <= 0 || filled + run > n) {
                    throw new IllegalArgumentException("mrle: run overruns grid");
                }
                for (long k = 0; k < run; k++) {
                    labels[filled++] = value;
                }
            }
            if (filled != n) {
                throw new IllegalArgumentException("mrle: incomplete label grid " + filled + "/" + n);
            }
            float[] confidence = new float[n];
            if ((flags & 1) != 0) {
                byte[] block = new byte[n * 4];
                in.readFully(block);
                ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(confidence);
            }
            byte[] boundary = new byte[n];
            if ((flags & 2) != 0) {
                byte[] packed = new byte[(n + 7) / 8];
                in.readFully(packed);
                for (int i = 0; i < n; i++) {
                    if ((packed[i >> 3] & (0x80 >>> (i & 7))) != 0) {
                        boundary[i] = 1;
                    }
                }
            }
            return new Mask(width, height, labels, confidence, boundary);
        } catch (EOFException e) {
            throw new IllegalArgumentException("mrle: truncated payload", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("mrle: corrupt payload: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] packBoundary(Mask mask) {
        int n = mask.size();
        byte[] packed = new byte[(n + 7) / 8];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (mask.boundary(i)) {
                any = true;
                packed[i >> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return any ? packed : null;
    }

    private static int readInt(byte[] data, int off) {
        return ((data[off] & 0xff) << 24) | ((data[off + 1] & 0xff) << 16)
                | ((data[off + 2] & 0xff) << 8) | (data[off + 3] & 0xff);
    }

    static void writeUvarint(DataOutputStream out, long value) throws IOException {
        while (value >= 0x80) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    static long readUvarint(DataInputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("uvarint truncated");
            }
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IllegalArgumentException("uvarint too long");
            }
        }
    }
}
