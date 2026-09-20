package gsb.service;

import gsb.model.ClassMapping;
import gsb.model.Source;
import gsb.model.VocabEntry;

import java.util.HashMap;
import java.util.Map;

/** Resolves per-class RGB colours from the vocabulary entry's displayColor (or a hash default). */
final class VocabPalette {
    private VocabPalette() {}

    static Map<Integer, int[]> colors(Source source, WorkspaceService svc, String sourceId) {
        Map<Integer, int[]> out = new HashMap<>();
        Map<String, String> mappingColor = new HashMap<>();
        for (VocabEntry e : source.vocabulary().entries()) {
            int[] rgb = parseColor(e.displayColor(), e.classId());
            out.put(e.classId(), rgb);
        }
        return out;
    }

    private static int[] parseColor(String hex, int classId) {
        if (hex != null && hex.matches("#?[0-9a-fA-F]{6}")) {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new int[]{
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)};
        }
        int v = (int) (classId * 2654435761L);
        return new int[]{(v & 0x7F) + 64, ((v >>> 8) & 0x7F) + 64, ((v >>> 16) & 0x7F) + 64};
    }
}
