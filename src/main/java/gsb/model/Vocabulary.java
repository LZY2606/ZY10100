package gsb.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned class table belonging to exactly one source. */
public record Vocabulary(String vocabVersion, List<VocabEntry> entries) {
    public Vocabulary {
        if (vocabVersion == null || vocabVersion.isBlank()) {
            throw new IllegalArgumentException("vocabVersion is required");
        }
        entries = List.copyOf(entries);
        Map<Integer, String> seen = new LinkedHashMap<>();
        for (VocabEntry e : entries) {
            if (seen.put(e.classId(), e.name()) != null) {
                throw new IllegalArgumentException("duplicate classId " + e.classId());
            }
        }
    }

    public Map<Integer, VocabEntry> byId() {
        Map<Integer, VocabEntry> map = new LinkedHashMap<>();
        for (VocabEntry e : entries) {
            map.put(e.classId(), e);
        }
        return map;
    }

    public String nameOf(int classId) {
        for (VocabEntry e : entries) {
            if (e.classId() == classId) {
                return e.name();
            }
        }
        return null;
    }
}
