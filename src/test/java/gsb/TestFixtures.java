package gsb;

import gsb.json.Json;
import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import gsb.mask.PngCodec;
import gsb.service.IngestService;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.store.WorkspaceStore;
import gsb.util.Hash;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared builders for tests. */
public final class TestFixtures {
    private TestFixtures() {}

    public static WorkspaceStore newStore() throws Exception {
        Path dir = FilesSupport.tempDir();
        return new WorkspaceStore(dir);
    }

    public static byte[] png(int width, int height, int seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = (x * 31 + y * 17 + seed) & 0xff;
                img.setRGB(x, y, 0xff000000 | (v << 16) | ((v * 3 & 0xff) << 8) | (v * 7 & 0xff));
            }
        }
        return PngCodec.encodeArgb(width, height, img.getRGB(0, 0, width, height, null, 0, width));
    }

    public static Workspace createImage(WorkspaceStore store, String id, int w, int h)
            throws Exception {
        Workspace ws = store.create(id);
        byte[] png = png(w, h, 3);
        IngestService.createImage(ws, Json.obj(
                "name", id, "width", w, "height", h,
                "orientation", 0, "spacingX", 1.0, "spacingY", 1.0,
                "fingerprint", Hash.sha256Hex(png)), png, "tester");
        return ws;
    }

    public static void addVocab(Workspace ws, String vocabId, String version,
                                String... idNames) {
        List<Object> cats = new java.util.ArrayList<>();
        for (int i = 0; i < idNames.length; i += 2) {
            cats.add(Json.obj("id", idNames[i], "name", idNames[i + 1],
                    "color", "#" + String.format("%06x", (i + 1) * 0x112233)));
        }
        IngestService.importVocabulary(ws, Json.obj("vocabularyId", vocabId,
                "name", vocabId, "version", version, "categories", cats), "tester");
    }

    public static void register(Workspace ws, String sourceId, String name) {
        IngestService.registerSource(ws, Json.obj("sourceId", sourceId,
                "name", name, "kind", "algorithm"), "tester");
    }

    public static Mask shapedMask(int w, int h, java.util.function.IntUnaryOperator labelAt) {
        Mask m = new Mask(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int label = labelAt.applyAsInt(i);
                m.labels()[i] = label;
                if (label > 0) {
                    m.confidence()[i] = 0.95f;
                }
            }
        }
        return m;
    }

    public static Model.SourceVersion addVersion(Workspace ws, String sourceId,
                                                 String vocabId, String vocabVersion,
                                                 Mask nativeMask, int orientation,
                                                 double spacing, int nativeW, int nativeH) {
        byte[] bytes = MrleCodec.encode(nativeMask);
        return IngestService.importSourceVersion(ws, Json.obj(
                "sourceId", sourceId,
                "versionLabel", "v-" + sourceId,
                "vocabularyId", vocabId,
                "vocabularyVersion", vocabVersion,
                "nativeWidth", nativeW,
                "nativeHeight", nativeH,
                "orientation", orientation,
                "spacingX", spacing,
                "spacingY", spacing,
                "maskFormat", "mrle1",
                "rawFingerprint", Hash.sha256Hex(bytes)), bytes, "tester");
    }

    public static Model.SourceVersion addVersion(Workspace ws, String sourceId,
                                                 String vocabId, String vocabVersion,
                                                 Mask nativeMask) {
        return addVersion(ws, sourceId, vocabId, vocabVersion, nativeMask, 0, 1.0,
                nativeMask.width(), nativeMask.height());
    }

    public static Model.SourceVersion addVersionAniso(Workspace ws, String sourceId,
                                                      String vocabId, String vocabVersion,
                                                      Mask nativeMask, double sx, double sy) {
        byte[] bytes = MrleCodec.encode(nativeMask);
        return IngestService.importSourceVersion(ws, Json.obj(
                "sourceId", sourceId,
                "versionLabel", "v-" + sourceId,
                "vocabularyId", vocabId,
                "vocabularyVersion", vocabVersion,
                "nativeWidth", nativeMask.width(),
                "nativeHeight", nativeMask.height(),
                "orientation", 0,
                "spacingX", sx,
                "spacingY", sy,
                "maskFormat", "mrle1",
                "rawFingerprint", Hash.sha256Hex(bytes)), bytes, "tester");
    }
}
