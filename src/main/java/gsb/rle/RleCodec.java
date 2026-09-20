package gsb.rle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact binary RLE for a single-channel integer label grid.
 *
 * Layout (big endian):
 *   int width, int height
 *   int runCount
 *   repeated: int label, int length   (row-major; runs may span several rows)
 *   int edgeByteLength
 *   byte[] edgeFlagBitmap             (1 bit per pixel, 1 = pixel touched by resampling boundary)
 */
public final class RleCodec {
    private RleCodec() {}

    public static byte[] encode(int width, int height, int[] labels, byte[] edgeFlags) {
        if (labels == null || labels.length != width * height) {
            throw new IllegalArgumentException("label grid length does not match geometry");
        }
        if (edgeFlags != null && edgeFlags.length != width * height) {
            throw new IllegalArgumentException("edge flag length does not match geometry");
        }
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < labels.length) {
            int label = labels[i];
            int run = 1;
            while (i + run < labels.length && labels[i + run] == label) {
                run++;
            }
            runs.add(new int[]{label, run});
            i += run;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(runs.size());
            for (int[] r : runs) {
                out.writeInt(r[0]);
                out.writeInt(r[1]);
            }
            int pixels = width * height;
            writeEdgeBitmap(out, pixels, edgeFlags);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("RLE encode failed", e);
        }
    }

    /**
     * Edge bitmap with a one-byte mode prefix:
     *   mode 0: all-zero (no follow-up bytes)
     *   mode 1: all-one (no follow-up bytes)
     *   mode 2: packed 1-bit-per-pixel bitmap follows
     */
    private static void writeEdgeBitmap(DataOutputStream out, int pixels, byte[] edgeFlags)
            throws IOException {
        boolean any = false;
        boolean all = edgeFlags != null;
        if (edgeFlags != null) {
            for (int p = 0; p < pixels; p++) {
                if (edgeFlags[p] != 0) {
                    any = true;
                } else {
                    all = false;
                }
            }
        } else {
            all = false;
        }
        if (!any) {
            out.writeInt(1);
            out.writeByte(0);
        } else if (all) {
            out.writeInt(1);
            out.writeByte(1);
        } else {
            int byteLen = (pixels + 7) / 8;
            byte[] bitmap = new byte[byteLen];
            for (int p = 0; p < pixels; p++) {
                if (edgeFlags[p] != 0) {
                    bitmap[p >> 3] |= (byte) (1 << (p & 7));
                }
            }
            out.writeInt(1 + byteLen);
            out.writeByte(2);
            out.write(bitmap);
        }
    }

    public static Grid decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int width = in.readInt();
            int height = in.readInt();
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("corrupt RLE: non-positive dimensions");
            }
            int pixels = width * height;
            int[] labels = new int[pixels];
            int runCount = in.readInt();
            if (runCount < 0 || runCount > pixels) {
                throw new IllegalArgumentException("corrupt RLE: invalid run count " + runCount);
            }
            int pos = 0;
            for (int r = 0; r < runCount; r++) {
                int label = in.readInt();
                int length = in.readInt();
                if (length <= 0 || pos + length > pixels) {
                    throw new IllegalArgumentException("corrupt RLE: invalid run length");
                }
                java.util.Arrays.fill(labels, pos, pos + length, label);
                pos += length;
            }
            if (pos != pixels) {
                throw new IllegalArgumentException("corrupt RLE: runs cover " + pos + "/" + pixels);
            }
            int stored = in.readInt();
            if (stored < 1) {
                throw new IllegalArgumentException("corrupt RLE: truncated edge bitmap");
            }
            byte[] block = in.readNBytes(stored);
            if (block.length != stored) {
                throw new IllegalArgumentException("corrupt RLE: truncated edge bitmap");
            }
            byte[] edges = new byte[pixels];
            int mode = block[0] & 0xFF;
            if (mode == 1) {
                java.util.Arrays.fill(edges, (byte) 1);
            } else if (mode == 2) {
                byte[] bitmap = java.util.Arrays.copyOfRange(block, 1, block.length);
                for (int p = 0; p < pixels; p++) {
                    if ((bitmap[p >> 3] & (1 << (p & 7))) != 0) {
                        edges[p] = 1;
                    }
                }
            } else if (mode != 0) {
                throw new IllegalArgumentException("corrupt RLE: unknown edge mode " + mode);
            }
            return new Grid(width, height, labels, edges);
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt RLE payload", e);
        }
    }

    /** Decoded raster. Label 0 means background/uncovered. */
    public record Grid(int width, int height, int[] labels, byte[] edgeFlags) {
        public int pixelCount() {
            return width * height;
        }
    }
}
