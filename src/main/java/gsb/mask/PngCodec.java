package gsb.mask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Palette-PNG bridge used for the immutable base image evidence and for
 * optional PNG mask import/export. The application's primary compressed mask
 * format is MRLE1; PNG is only an interchange envelope.
 */
public final class PngCodec {
    private PngCodec() {}

    /** ARGB raster bytes (width*height*4, 0xAARRGGBB per pixel in row order). */
    public static byte[] encodeArgb(int width, int height, int[] argb) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("argb length mismatch");
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        WritableRaster raster = img.getRaster();
        int[] px = new int[4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            px[0] = (p >>> 16) & 0xff;
            px[1] = (p >>> 8) & 0xff;
            px[2] = p & 0xff;
            px[3] = (p >>> 24) & 0xff;
            raster.setPixel(i % width, i / width, px);
        }
        return writePng(img);
    }

    public static BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new IllegalArgumentException("not a PNG image");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("png decode failed: " + e.getMessage(), e);
        }
    }

    public static int[] toArgb(BufferedImage img) {
        BufferedImage argb = img;
        if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
            argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(img, 0, 0, null);
        }
        return argb.getRGB(0, 0, argb.getWidth(), argb.getHeight(), null, 0, argb.getWidth());
    }

    public static byte[] encodeMaskPng(Mask mask) {
        int colors = Math.max(256, Math.min(65536, mask.maxLabel() + 1));
        int bits = colors <= 256 ? 8 : 16;
        int tableSize = 1 << bits;
        byte[] r = new byte[tableSize];
        byte[] g = new byte[tableSize];
        byte[] b = new byte[tableSize];
        byte[] a = new byte[tableSize];
        for (int i = 1; i < tableSize; i++) {
            int h = (i * 2654435761L == 0 ? 1 : (int) (i * 2654435761L));
            r[i] = (byte) (h & 0xc0);
            g[i] = (byte) ((h >>> 8) & 0xa0);
            b[i] = (byte) ((h >>> 16) & 0xe0);
            a[i] = (byte) 0xff;
        }
        r[0] = g[0] = b[0] = 0;
        a[0] = 0;
        IndexColorModel cm = new IndexColorModel(bits, tableSize, r, g, b, a);
        int type = bits == 8 ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_USHORT_GRAY;
        BufferedImage img;
        WritableRaster raster;
        if (bits == 8) {
            img = new BufferedImage(mask.width(), mask.height(), type, cm);
            raster = img.getRaster();
            for (int i = 0; i < mask.size(); i++) {
                raster.setSample(i % mask.width(), i / mask.width(), 0,
                        Math.max(0, mask.label(i)) & 0xff);
            }
        } else {
            img = new BufferedImage(mask.width(), mask.height(), type);
            raster = img.getRaster();
            for (int i = 0; i < mask.size(); i++) {
                raster.setSample(i % mask.width(), i / mask.width(), 0,
                        Math.max(0, mask.label(i)) & 0xffff);
            }
        }
        return writePng(img);
    }

    /**
     * Decode an index-PNG (or grayscale PNG) into labels. Index 0 maps to 0.
     * Labels above the palette/gray range are taken from raw sample values.
     */
    public static Mask decodeMaskPng(byte[] png, int expectedWidth, int expectedHeight) {
        BufferedImage img = decode(png);
        if (img.getWidth() != expectedWidth || img.getHeight() != expectedHeight) {
            throw new IllegalArgumentException("png mask dimensions " + img.getWidth() + "x"
                    + img.getHeight() + " do not match declared " + expectedWidth + "x"
                    + expectedHeight);
        }
        WritableRaster raster = img.getRaster();
        int n = expectedWidth * expectedHeight;
        int[] labels = new int[n];
        for (int y = 0; y < expectedHeight; y++) {
            for (int x = 0; x < expectedWidth; x++) {
                int sample = raster.getSample(x, y, 0);
                labels[y * expectedWidth + x] = sample;
            }
        }
        return new Mask(expectedWidth, expectedHeight, labels, new float[n], new byte[n]);
    }

    private static byte[] writePng(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", bos)) {
                throw new IllegalStateException("no PNG writer available");
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("png encode failed", e);
        }
    }
}
