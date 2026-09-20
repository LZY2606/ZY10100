package gsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.geom.Geometry;
import gsb.model.Polygon;
import gsb.model.VocabEntry;
import gsb.model.Vocabulary;
import gsb.service.ValidationException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Parsing helpers for request JSON. */
final class JsonCodecs {
    private JsonCodecs() {}

    static Geometry geometry(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            throw ValidationException.of("MISSING_FIELD", "canonicalGeometry is required");
        }
        return new Geometry(
                n.path("width").asInt(),
                n.path("height").asInt(),
                n.path("spacingX").asDouble(),
                n.path("spacingY").asDouble(),
                n.path("orientation").asText(""),
                n.path("originX").asDouble(0),
                n.path("originY").asDouble(0));
    }

    static Vocabulary vocabulary(JsonNode n) {
        String version = n.path("vocabVersion").asText(null);
        if (version == null || version.isBlank()) {
            throw ValidationException.of("MISSING_FIELD", "vocabulary.vocabVersion is required");
        }
        List<VocabEntry> entries = new ArrayList<>();
        for (JsonNode e : n.path("entries")) {
            entries.add(new VocabEntry(e.path("classId").asInt(),
                    e.path("name").asText(""), e.path("displayColor").asText(null)));
        }
        return new Vocabulary(version, entries);
    }

    static Polygon polygon(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            throw ValidationException.of("MISSING_FIELD", "region is required");
        }
        JsonNode ring = n.path("ring");
        if (!ring.isArray() || ring.size() < 3) {
            throw ValidationException.of("BAD_POLYGON", "region.ring needs >= 3 [x,y] points");
        }
        List<double[]> points = new ArrayList<>();
        for (JsonNode p : ring) {
            points.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
        }
        return new Polygon(points);
    }

    static int[] intArray(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isTextual()) {
            byte[] decoded = Base64.getDecoder().decode(n.asText());
            int[] out = new int[decoded.length / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = ((decoded[i * 4] & 0xFF) << 24)
                        | ((decoded[i * 4 + 1] & 0xFF) << 16)
                        | ((decoded[i * 4 + 2] & 0xFF) << 8)
                        | (decoded[i * 4 + 3] & 0xFF);
            }
            return out;
        }
        if (!n.isArray()) {
            throw ValidationException.of("MISSING_FIELD", field + " must be an int array");
        }
        int[] out = new int[n.size()];
        for (int i = 0; i < n.size(); i++) {
            out[i] = n.get(i).asInt();
        }
        return out;
    }

    static double[] doubleArray(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw ValidationException.of("BAD_FIELD", field + " must be a number array");
        }
        double[] out = new double[n.size()];
        for (int i = 0; i < n.size(); i++) {
            out[i] = n.get(i).asDouble();
        }
        return out;
    }
}
