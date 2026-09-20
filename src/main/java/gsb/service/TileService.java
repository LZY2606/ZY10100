package gsb.service;

import gsb.geom.Geometry;
import gsb.model.ComparisonResult;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.rle.RleCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders 256x256 PNG tiles of masks, comparisons and consensus in canonical pixel
 * coordinates. Tiles are generated on demand from immutable blobs; no external image service
 * is used.
 */
public final class TileService {
    public static final int TILE = 256;

    // ARGB palette
    private static final int TRANSPARENT = 0x00000000;
    private static final int AGREE = 0x4000C800;
    private static final int DISAGREE = 0x90FF2020;
    private static final int UNCOVERED = 0x00000000;
    private static final int EDGE = 0xC0FFCC00;
    private static final int EXCLUDED = 0x30808080;

    public byte[] sourceTile(WorkspaceService svc, String sourceId, int tx, int ty) {
        Workspace ws = svc.workspace();
        Source src = ws.source(sourceId);
        if (src == null) {
            throw ValidationException.of("SOURCE_MISSING", sourceId);
        }
        RleCodec.Grid grid = RleCodec.decode(svc.store().blobs().get(src.maskBlobSha256()));
        Map<Integer, int[]> palette = VocabPalette.colors(src, svc, sourceId);
        int[] fill = new int[TILE * TILE];
        java.util.Arrays.fill(fill, TRANSPARENT);
        paint(grid, tx, ty, fill, (label, edge) -> {
            if (label == 0) {
                return TRANSPARENT;
            }
            int[] base = palette.getOrDefault(label, new int[]{150, 150, 150});
            int argb = 0x88000000 | ((0xFF & base[0]) << 16) | ((0xFF & base[1]) << 8) | (0xFF & base[2]);
            return edge ? blend(argb, EDGE) : argb;
        });
        return png(fill);
    }

    public byte[] comparisonTile(WorkspaceService svc, ComparisonResult result, int tx, int ty) {
        RleCodec.Grid grid = RleCodec.decode(
                svc.store().blobs().get(result.classRasterBlobSha256()));
        int[] fill = new int[TILE * TILE];
        java.util.Arrays.fill(fill, TRANSPARENT);
        paint(grid, tx, ty, fill, (label, edge) -> switch (label) {
            case ComparisonResult.CAT_AGREE -> AGREE;
            case ComparisonResult.CAT_DISAGREE -> DISAGREE;
            case ComparisonResult.CAT_RESAMPLE_EDGE -> EDGE;
            case ComparisonResult.CAT_EXCLUDED_CONF -> EXCLUDED;
            default -> UNCOVERED;
        });
        return png(fill);
    }

    public byte[] consensusTile(WorkspaceService svc, int tx, int ty) {
        BuiltRaster raster = new ConsensusBuilder().build(svc);
        Workspace ws = svc.workspace();
        Geometry g = ws.canonicalGeometry();
        int[] fill = new int[TILE * TILE];
        java.util.Arrays.fill(fill, TRANSPARENT);
        int width = g.width();
        for (int py = 0; py < TILE; py++) {
            int y = ty * TILE + py;
            for (int px = 0; px < TILE; px++) {
                int x = tx * TILE + px;
                if (x < 0 || y < 0 || x >= width || y >= g.height()) {
                    continue;
                }
                int i = y * width + x;
                int label = raster.labels()[i];
                if (label != 0) {
                    fill[py * TILE + px] = 0xAA3060FF | ((label * 4099 + 128) & 0x00FFFF00);
                }
                if (raster.edges()[i] != 0) {
                    fill[py * TILE + px] = blend(fill[py * TILE + px], EDGE);
                }
            }
        }
        return png(fill);
    }

    private interface PixelMapper {
        int color(int label, boolean edge);
    }

    private void paint(RleCodec.Grid grid, int tx, int ty, int[] fill, PixelMapper mapper) {
        int width = grid.width();
        int height = grid.height();
        for (int py = 0; py < TILE; py++) {
            int y = ty * TILE + py;
            for (int px = 0; px < TILE; px++) {
                int x = tx * TILE + px;
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    continue;
                }
                int i = y * width + x;
                fill[py * TILE + px] = mapper.color(grid.labels()[i], grid.edgeFlags()[i] != 0);
            }
        }
    }

    private static int blend(int bottom, int top) {
        int ta = (top >>> 24) & 0xFF;
        if (ta == 0) {
            return bottom;
        }
        int ba = (bottom >>> 24) & 0xFF;
        int outA = Math.min(255, ta + ba);
        int outR = (((top >>> 16) & 0xFF) * ta + ((bottom >>> 16) & 0xFF) * (255 - ta)) / 255;
        int outG = (((top >>> 8) & 0xFF) * ta + ((bottom >>> 8) & 0xFF) * (255 - ta)) / 255;
        int outB = ((top & 0xFF) * ta + (bottom & 0xFF) * (255 - ta)) / 255;
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private byte[] png(int[] argb) {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        int[] data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(argb, 0, data, 0, argb.length);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", bos);
        } catch (IOException e) {
            throw new IllegalStateException("PNG encode failed", e);
        }
        return bos.toByteArray();
    }
}
