package gsb.service;

import gsb.geo.Geometry;
import gsb.json.Json;
import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import gsb.mask.PngCodec;
import gsb.store.ApiException;
import gsb.store.EvidenceStore;
import gsb.store.Model;
import gsb.store.Workspace;
import gsb.util.Hash;
import gsb.util.Ids;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Image / vocabulary / source-version ingestion with strict validation. */
public final class IngestService {
    private IngestService() {}

    public static Model.ImageInfo createImage(Workspace ws, Map<String, Object> req,
                                              byte[] imageBytes, String actor) {
        if (ws.model().image != null) {
            throw ApiException.conflict("IMAGE_EXISTS",
                    "image workspace " + ws.imageId + " already has a base image");
        }
        int width = requireInt(req, "width");
        int height = requireInt(req, "height");
        int orientation = Geometry.normalizeOrientation(Json.integer(req, "orientation", 0));
        double sx = Json.dbl(req, "spacingX", 1);
        double sy = Json.dbl(req, "spacingY", 1);
        Geometry.checkSpacing(sx, sy);
        String declared = Json.str(req, "fingerprint");
        if (declared == null || !declared.matches("[0-9a-f]{64}")) {
            throw ApiException.badRequest("BAD_FINGERPRINT",
                    "image fingerprint (sha256 hex) is required before upload");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "image bytes are required");
        }
        BufferedImage decoded;
        try {
            decoded = PngCodec.decode(imageBytes);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("BAD_IMAGE", e.getMessage());
        }
        if (decoded.getWidth() != width || decoded.getHeight() != height) {
            throw ApiException.unprocessable("DIMENSION_MISMATCH",
                    "declared dimensions " + width + "x" + height
                            + " do not match received image " + decoded.getWidth() + "x"
                            + decoded.getHeight())
                    .detail("orientation=" + orientation + " spacing=" + sx + "/" + sy)
                    .detail("fix metadata or re-export the image; comparison was not performed");
        }

        EvidenceStore.Stored stored;
        try {
            stored = ws.evidence.put(imageBytes, declared);
        } catch (EvidenceStore.FingerprintMismatchException e) {
            throw ApiException.unprocessable("FINGERPRINT_MISMATCH", e.getMessage())
                    .detail("declared: " + e.declared)
                    .detail("actual:   " + e.actual)
                    .detail("matching width/height must NOT be used to bypass this check");
        }
        String imageId = ws.imageId;
        Map<String, Object> payload = Json.obj(
                "imageId", imageId,
                "name", orDefault(req.get("name"), imageId),
                "width", width,
                "height", height,
                "orientation", orientation,
                "spacingX", sx,
                "spacingY", sy,
                "fingerprint", stored.sha256(),
                "rawEvidence", stored.sha256(),
                "rawFormat", "png");
        ws.append("image.created", payload, actor);
        return ws.model().image;
    }

    public static void importVocabulary(Workspace ws, Map<String, Object> req, String actor) {
        String vocabId = requireStr(req, "vocabularyId");
        String version = requireStr(req, "version");
        List<Object> cats = Json.list(req, "categories");
        if (cats == null || cats.isEmpty()) {
            throw ApiException.badRequest("EMPTY_VOCABULARY", "categories are required");
        }
        String key = vocabId + "@" + version;
        if (ws.model().vocabularies.containsKey(key)) {
            throw ApiException.conflict("VOCABULARY_EXISTS",
                    "vocabulary " + key + " already imported; same name with a different version"
                            + " is stored as a distinct vocabulary");
        }
        List<Object> clean = new ArrayList<>();
        for (Object raw : cats) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cm = (Map<String, Object>) raw;
            clean.add(Json.obj("id", requireStr(cm, "id"),
                    "name", requireStr(cm, "name"),
                    "color", orDefault(cm.get("color"), colorFor(requireStr(cm, "id")))));
        }
        ws.append("vocabulary.imported", Json.obj(
                "vocabularyId", vocabId,
                "name", orDefault(req.get("name"), vocabId),
                "version", version,
                "categories", clean), actor);
    }

    public static String registerSource(Workspace ws, Map<String, Object> req, String actor) {
        String sourceId = orDefault(req.get("sourceId"), Ids.create("src")).toString();
        if (ws.model().sources.containsKey(sourceId)) {
            throw ApiException.conflict("SOURCE_EXISTS", "source " + sourceId + " exists");
        }
        ws.append("source.registered", Json.obj(
                "sourceId", sourceId,
                "name", orDefault(req.get("name"), sourceId),
                "kind", orDefault(req.get("kind"), "algorithm")), actor);
        return sourceId;
    }

    public static Model.SourceVersion importSourceVersion(Workspace ws, Map<String, Object> req,
                                                          byte[] maskBytes, String actor) {
        Model m = ws.model();
        if (m.image == null) {
            throw ApiException.badRequest("NO_IMAGE", "create the base image first");
        }
        String sourceId = requireStr(req, "sourceId");
        Model.Source source = m.sources.get(sourceId);
        if (source == null) {
            throw ApiException.notFound("unknown source " + sourceId
                    + " (register it first)");
        }
        String vocabId = requireStr(req, "vocabularyId");
        String vocabVersion = requireStr(req, "vocabularyVersion");
        Model.Vocabulary vocab = m.vocabularies.get(vocabId + "@" + vocabVersion);
        if (vocab == null) {
            throw ApiException.badRequest("UNKNOWN_VOCABULARY",
                    "vocabulary " + vocabId + "@" + vocabVersion + " not imported");
        }
        int nativeW = requireInt(req, "nativeWidth");
        int nativeH = requireInt(req, "nativeHeight");
        int orientation = Geometry.normalizeOrientation(Json.integer(req, "orientation", 0));
        double spacingX = Json.dbl(req, "spacingX", m.image.spacingX);
        double spacingY = Json.dbl(req, "spacingY", m.image.spacingY);
        String declaredFingerprint = requireStr(req, "rawFingerprint");
        if (!(spacingX > 0 && spacingY > 0)
                || Math.abs(spacingX - spacingY) > Geometry.SPACING_TOLERANCE
                * Math.max(1.0, Math.max(spacingX, spacingY))) {
            throw ApiException.unprocessable("SPACING_MISMATCH",
                    "anisotropic or non-positive pixel spacing: source "
                            + spacingX + "/" + spacingY)
                    .detail("source: " + sourceId)
                    .detail("declared fingerprint: " + declaredFingerprint);
        }
        String format = orDefault(req.get("maskFormat"), "mrle1").toString();
        if (maskBytes == null || maskBytes.length == 0) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "mask bytes are required");
        }

        // Isotropic spacing is required; source/image spacing may differ and is
        // reconciled by resampling (the derived raster records the rule version).
        if (Math.abs(spacingX - spacingY) > Geometry.SPACING_TOLERANCE
                * Math.max(1, Math.max(spacingX, spacingY))
                || Math.abs(m.image.spacingX - m.image.spacingY) > Geometry.SPACING_TOLERANCE
                * Math.max(1, Math.max(m.image.spacingX, m.image.spacingY))) {
            throw ApiException.unprocessable("SPACING_MISMATCH",
                    "anisotropic pixel spacing cannot be resampled without distortion: source "
                            + spacingX + "/" + spacingY + " vs image "
                            + m.image.spacingX + "/" + m.image.spacingY)
                    .detail("source: " + sourceId)
                    .detail("declared fingerprint: " + declaredFingerprint);
        }

        EvidenceStore.Stored raw;
        try {
            raw = ws.evidence.put(maskBytes, declaredFingerprint);
        } catch (EvidenceStore.FingerprintMismatchException e) {
            throw ApiException.unprocessable("FINGERPRINT_MISMATCH", e.getMessage())
                    .detail("declared: " + e.declared)
                    .detail("actual:   " + e.actual);
        }

        Mask nativeMask;
        try {
            if ("png".equals(format)) {
                nativeMask = PngCodec.decodeMaskPng(maskBytes, nativeW, nativeH);
            } else if ("mrle1".equals(format)) {
                Mask parsed = MrleCodec.decode(maskBytes);
                if (parsed.width() != nativeW || parsed.height() != nativeH) {
                    throw ApiException.unprocessable("DIMENSION_MISMATCH",
                            "mask native dimensions " + parsed.width() + "x" + parsed.height()
                                    + " differ from declared " + nativeW + "x" + nativeH);
                }
                nativeMask = parsed;
            } else {
                throw ApiException.badRequest("BAD_FORMAT",
                        "unsupported maskFormat " + format + " (mrle1|png)");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("BAD_MASK", "cannot decode mask: " + e.getMessage());
        }

        List<String> diagnostics = new ArrayList<>();
        for (int label = 1; label <= nativeMask.maxLabel(); label++) {
            if (!vocab.categories.containsKey(Integer.toString(label))) {
                diagnostics.add("label " + label + " is not declared in vocabulary "
                        + vocabId + "@" + vocabVersion + " (mask claims "
                        + nativeMask.countLabel(label) + " pixels)");
            }
        }
        if (!diagnostics.isEmpty()) {
            throw ApiException.unprocessable("LABEL_OUT_OF_VOCABULARY",
                    "mask labels exceed / miss the declared category table")
                    .detail(diagnostics.toArray(new String[0]));
        }

        Geometry.ResampleResult rr = Geometry.resample(nativeMask, nativeW, nativeH,
                spacingX, m.image.spacingX, orientation, m.image.width, m.image.height);
        byte[] derived = MrleCodec.encode(rr.mask);
        String derivedHash = Hash.sha256Hex(derived);
        EvidenceStore.Stored derivedStored = ws.evidence.put(derived, derivedHash);

        String versionId = Ids.create("ver");
        Map<String, Object> payload = Json.obj(
                "sourceId", sourceId,
                "versionId", versionId,
                "versionLabel", orDefault(req.get("versionLabel"), versionId),
                "vocabularyId", vocabId,
                "vocabularyVersion", vocabVersion,
                "nativeWidth", nativeW,
                "nativeHeight", nativeH,
                "orientation", orientation,
                "spacingX", spacingX,
                "spacingY", spacingY,
                "rawEvidence", raw.sha256(),
                "rawFormat", format,
                "rawFingerprint", raw.sha256(),
                "rawSize", raw.size(),
                "resampled", rr.resampled,
                "boundaryPixels", rr.boundaryPixels,
                "outOfBoundsPixels", rr.outOfBoundsPixels,
                "derivedEvidence", derivedStored.sha256(),
                "derivedFingerprint", derivedStored.sha256(),
                "derivedFromRuleVersion", gsb.analysis.Analysis.RULE_VERSION,
                "maxLabel", nativeMask.maxLabel());
        ws.append("source.version.imported", payload, actor);
        ws.append("source.activated", Json.obj(
                "sourceId", sourceId, "versionId", versionId), actor);
        ws.invalidateMaskCache();
        return ws.model().version(versionId);
    }

    private static int requireInt(Map<String, Object> req, String key) {
        Object v = req.get(key);
        if (!(v instanceof Number)) {
            throw ApiException.badRequest("MISSING_FIELD", "required numeric field: " + key);
        }
        int n = ((Number) v).intValue();
        if (n <= 0) {
            throw ApiException.badRequest("BAD_FIELD", key + " must be positive");
        }
        return n;
    }

    private static String requireStr(Map<String, Object> req, String key) {
        String v = Json.str(req, key);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELD", "required field: " + key);
        }
        return v;
    }

    private static Object orDefault(Object v, Object dflt) {
        return v == null || v.toString().isBlank() ? dflt : v;
    }

    private static String colorFor(String id) {
        int h = (id.hashCode() * 0x9e3779b1) | 0;
        return String.format("#%06x", h & 0xffffff);
    }
}
