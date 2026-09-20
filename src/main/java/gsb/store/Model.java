package gsb.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable projected state for one image workspace. */
public final class Model {
    public ImageInfo image;
    public double confidenceThreshold = 0.0;
    public String ruleVersion = "pairwise-gsb-v1";

    public final LinkedHashMap<String, Vocabulary> vocabularies = new LinkedHashMap<>();
    public final LinkedHashMap<String, Source> sources = new LinkedHashMap<>();
    public final LinkedHashMap<String, MappingGroup> mappingGroups = new LinkedHashMap<>();
    public final LinkedHashMap<String, Decision> decisions = new LinkedHashMap<>();
    public final LinkedHashMap<String, ReleaseInfo> releases = new LinkedHashMap<>();
    public long baseVersion = 0;

    public List<SourceVersion> activeVersions() {
        List<SourceVersion> result = new ArrayList<>();
        for (Source s : sources.values()) {
            SourceVersion v = s.versions.get(s.activeVersionId);
            if (v != null && !v.retracted) {
                result.add(v);
            }
        }
        return result;
    }

    public SourceVersion version(String versionId) {
        for (Source s : sources.values()) {
            SourceVersion v = s.versions.get(versionId);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public Source sourceOfVersion(String versionId) {
        for (Source s : sources.values()) {
            if (s.versions.containsKey(versionId)) {
                return s;
            }
        }
        return null;
    }

    public static final class ImageInfo {
        public String id;
        public String name;
        public int width;
        public int height;
        public int orientation;
        public double spacingX;
        public double spacingY;
        public String fingerprint;
        public String rawEvidence;
        public String rawFormat;
        public String createdAt;

        public boolean geometryMatches(int w, int h, int orientation, double sx, double sy) {
            return width == w && height == h && this.orientation == orientation
                    && approx(spacingX, sx) && approx(spacingY, sy);
        }

        private static boolean approx(double a, double b) {
            return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.max(a, b));
        }
    }

    public static final class Vocabulary {
        public String id;
        public String name;
        public String version;
        public String createdAt;
        public final LinkedHashMap<String, CategoryDef> categories = new LinkedHashMap<>();
    }

    public static final class CategoryDef {
        public String id;
        public String name;
        public String color;
    }

    public static final class Source {
        public String id;
        public String name;
        public String kind;
        public String activeVersionId;
        public final LinkedHashMap<String, SourceVersion> versions = new LinkedHashMap<>();
    }

    public static final class SourceVersion {
        public String id;
        public String sourceId;
        public String versionLabel;
        public String createdAt;
        public String actor;

        public String vocabularyId;
        public String vocabularyVersion;

        public int nativeWidth;
        public int nativeHeight;
        public int orientation;
        public double spacingX;
        public double spacingY;

        public String rawEvidence;
        public String rawFormat;
        public String rawFingerprint;
        public long rawSize;

        public boolean resampled;
        public long boundaryPixels;
        public long outOfBoundsPixels;
        public String derivedEvidence;
        public String derivedFingerprint;
        public String derivedFromRuleVersion;
        public int maxLabel;

        public boolean retracted;
        public String retractedAt;
    }

    /**
     * Equivalence group of category refs. Name-similar refs from different
     * vocabulary versions are never auto-merged: a group starts PROPOSED and
     * must be CONFIRMED before publication.
     */
    public static final class MappingGroup {
        public String id;
        public String status;
        public String canonicalName;
        public String proposedAt;
        public String confirmedAt;
        public String actor;
        public final List<CategoryRef> refs = new ArrayList<>();
    }

    public record CategoryRef(String vocabularyId, String vocabularyVersion, String categoryId) {
        public String key() {
            return vocabularyId + "@" + vocabularyVersion + ":" + categoryId;
        }
    }

    /** Immutable region-level decision event (accept / correct / pending). */
    public static final class Decision {
        public String id;
        public String kind;
        public String regionId;
        public String acceptedSourceId;
        public String basedOnVersionId;
        public String polygonHash;
        public long areaPixels;
        public List<List<Double>> polygon;
        public Integer correctionClass;
        public String correctionVocabularyId;
        public String createdAt;
        public String actor;
        public long basedOnLogSeq;
        public long committedLogSeq;
        public String undoneBy;
        public String undoOf;
        public boolean stale;
        public String staleReason;
    }

    public static final class ReleaseInfo {
        public String id;
        public String createdAt;
        public String actor;
        public long logSeq;
        public long consensusBoundaryPixels;
        public long outOfBoundsPixels;
        public String manifestHash;
    }
}
