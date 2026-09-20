package gsb.service;

import gsb.analysis.Analysis;
import gsb.mask.Mask;
import gsb.mask.PngCodec;
import gsb.store.Model;
import gsb.store.Workspace;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * XYZ-style tiled overlay renderer. The minimal zoom level shows the whole
 * image in one 256px tile; higher zoom levels are exact powers of two. Tiles
 * render the base image + per-source class colors + agreement/disagreement
 * overlay, and are cached on disk keyed by content version.
 */
public final class TileService {
    public static final int TILE = 256;

    private TileService() {}

    public static int minZoom(int width, int height) {
        int span = Math.max(width, height);
        int z = 0;
        while ((TILE << z) < span) {
            z++;
        }
        return z;
    }

    public static int maxZoom(int width, int height) {
        int span = Math.max(width, height);
        int z = minZoom(width, height);
        while ((TILE << (z + 1)) <= span * 4 && z < 16) {
            z++;
        }
        return z;
    }

    public static byte[] renderTile(Workspace ws, Analysis analysis, String layer,
                                    int z, int x, int y) throws IOException {
        Model m = ws.model();
        int width = m.image.width;
        int height = m.image.height;
        int minZ = minZoom(width, height);
        if (z < minZ) {
            throw new IllegalArgumentException("zoom " + z + " below min zoom " + minZ);
        }
        int level = z - minZ;
        double scale = (double) (TILE << level) / Math.max(width, height);
        int imgW = (int) Math.round(width * scale);
        int imgH = (int) Math.round(height * scale);
        if (x < 0 || y < 0 || (x * TILE) >= imgW || (y * TILE) >= imgH) {
            return transparentTile();
        }

        Path cacheDir = ws.dir.resolve("cache").resolve("tiles")
                .resolve(layer).resolve(Integer.toString(z));
        Path cacheFile = cacheDir.resolve(x + "_" + y + "_" + cacheKey(ws, layer) + ".png");
        if (Files.exists(cacheFile)) {
            return Files.readAllBytes(cacheFile);
        }

        BufferedImage base = PngCodec.decode(ws.evidence.read(m.image.rawEvidence));
        BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, TILE, TILE);

        int srcX = (int) Math.floor(x * TILE / scale);
        int srcY = (int) Math.floor(y * TILE / scale);
        int srcW = Math.min(width - srcX, (int) Math.ceil(TILE / scale) + 1);
        int srcH = Math.min(height - srcY, (int) Math.ceil(TILE / scale) + 1);
        if (srcW <= 0 || srcH <= 0) {
            g.dispose();
            return saveAndReturn(tile, cacheFile);
        }
        BufferedImage region = base.getSubimage(srcX, srcY, srcW, srcH);
        int drawW = (int) Math.round(srcW * scale);
        int drawH = (int) Math.round(srcH * scale);
        int offsetX = (int) Math.round(srcX * scale) - x * TILE;
        int offsetY = (int) Math.round(srcY * scale) - y * TILE;
        g.drawImage(region, offsetX, offsetY, drawW, drawH, null);

        if (!"base".equals(layer)) {
            renderOverlay(ws, analysis, layer, g, scale, srcX, srcY, srcW, srcH,
                    offsetX, offsetY, drawW, drawH);
        }
        g.dispose();
        return saveAndReturn(tile, cacheFile);
    }

    private static void renderOverlay(Workspace ws, Analysis analysis, String layer,
                                      Graphics2D g, double scale, int srcX, int srcY,
                                      int srcW, int srcH, int offsetX, int offsetY,
                                      int drawW, int drawH) {
        Model m = ws.model();
        int pixelW = Math.max(1, (int) Math.ceil(scale));
        Map<String, Color> classColors = new HashMap<>();
        if (layer.startsWith("source:")) {
            String versionId = layer.substring("source:".length());
            Model.SourceVersion v = m.version(versionId);
            if (v == null) {
                return;
            }
            Model.Vocabulary vocab = m.vocabularies.get(v.vocabularyId + "@" + v.vocabularyVersion);
            Mask mask = ws.alignedMask(v);
            for (int py = 0; py < srcH; py++) {
                for (int px = 0; px < srcW; px++) {
                    int label = mask.labelAt(srcX + px, srcY + py);
                    if (label <= 0) {
                        continue;
                    }
                    Color c = vocab != null && vocab.categories.containsKey(Integer.toString(label))
                            ? Color.decode(vocab.categories.get(Integer.toString(label)).color)
                            : Color.MAGENTA;
                    g.setColor(withAlpha(c, 160));
                    g.fillRect(offsetX + (int) Math.round(px * scale),
                            offsetY + (int) Math.round(py * scale), pixelW, pixelW);
                }
            }
            return;
        }
        if ("consensus".equals(layer) || "disagree".equals(layer)) {
            for (int py = 0; py < srcH; py++) {
                for (int px = 0; px < srcW; px++) {
                    int si = (srcY + py) * m.image.width + srcX + px;
                    byte st = analysis.status[si];
                    if (layer.equals("disagree")) {
                        if (st == Analysis.DISAGREE) {
                            g.setColor(withAlpha(Color.RED, 180));
                            g.fillRect(offsetX + (int) Math.round(px * scale),
                                    offsetY + (int) Math.round(py * scale), pixelW, pixelW);
                        }
                        continue;
                    }
                    if (st == Analysis.AGREE) {
                        g.setColor(withAlpha(new Color(60, 180, 90), 110));
                    } else if (st == Analysis.DISAGREE) {
                        g.setColor(withAlpha(new Color(220, 60, 50), 170));
                    } else {
                        continue;
                    }
                    g.fillRect(offsetX + (int) Math.round(px * scale),
                            offsetY + (int) Math.round(py * scale), pixelW, pixelW);
                    if (analysis.boundary[si] != 0 && pixelW >= 2) {
                        g.setColor(withAlpha(Color.YELLOW, 200));
                        g.drawRect(offsetX + (int) Math.round(px * scale),
                                offsetY + (int) Math.round(py * scale),
                                Math.max(1, pixelW - 1), Math.max(1, pixelW - 1));
                    }
                }
            }
        }
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static byte[] saveAndReturn(BufferedImage tile, Path cacheFile) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(tile, "png", bos);
        byte[] bytes = bos.toByteArray();
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, bytes);
        return bytes;
    }

    private static byte[] transparentTile() throws IOException {
        BufferedImage t = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(t, "png", bos);
        return bos.toByteArray();
    }

    private static String cacheKey(Workspace ws, String layer) {
        return Integer.toHexString((ws.log.lastHash() + layer + ws.model().confidenceThreshold)
                .hashCode());
    }
}
