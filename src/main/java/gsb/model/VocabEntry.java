package gsb.model;

/**
 * One class table row. Equality of {@code name} across two different {@code vocabVersion}
 * values does NOT imply the classes can be merged — an explicit, confirmed ClassMapping is
 * always required.
 */
public record VocabEntry(int classId, String name, String displayColor) {
    public VocabEntry {
        if (classId == 0) {
            throw new IllegalArgumentException("classId 0 is reserved for background/uncovered");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vocab entry name is required");
        }
    }
}
