package gsb.service;

import gsb.model.ClassMapping;
import gsb.model.Source;
import gsb.model.VocabEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages class-table mappings across vocabulary versions. Same-named classes are *proposed*
 * onto one canonical key, never auto-confirmed: the persisted {@code confirmed=false} state
 * blocks release until a human acknowledges that e.g. "liver@vocab-2" really means the same
 * tissue as "liver@vocab-9".
 */
public final class MappingService {

    /** Canonical keys are the normalised class name; different versions still need confirmation. */
    public static String canonicalKeyFor(String className) {
        return "class:" + className.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    /** Propose (but do not confirm) mappings for every class of a newly ingested source. */
    public List<ClassMapping> proposeFor(WorkspaceService svc, Source source) {
        List<ClassMapping> created = new ArrayList<>();
        for (VocabEntry entry : source.vocabulary().entries()) {
            String key = canonicalKeyFor(entry.name());
            boolean alreadyMapped = svc.workspace().mappings().values().stream()
                    .anyMatch(m -> m.sourceId().equals(source.sourceId())
                            && m.classId() == entry.classId());
            if (alreadyMapped) {
                continue;
            }
            // If another version used this canonical key before, the new link stays unconfirmed;
            // the UI highlights the version difference explicitly.
            ClassMapping mapping = new ClassMapping(
                    svc.newId(), source.sourceId(), entry.classId(), entry.name(),
                    source.vocabulary().vocabVersion(), key, false, null, null);
            svc.putMapping(mapping);
            created.add(mapping);
        }
        return created;
    }

    public ClassMapping confirm(WorkspaceService svc, String mappingId, String user) {
        ClassMapping current = svc.workspace().mappings().get(mappingId);
        if (current == null) {
            throw ValidationException.of("MAPPING_MISSING", "no mapping " + mappingId);
        }
        ClassMapping confirmed = current.withConfirmation(user, Instant.now(svc.clock()));
        svc.putMapping(confirmed);
        return confirmed;
    }

    /**
     * All classes referenced by active sources must have a confirmed mapping before release.
     * Returns human-readable descriptions of each unconfirmed link.
     */
    public List<String> unconfirmedDescriptions(WorkspaceService svc) {
        List<String> out = new ArrayList<>();
        for (ClassMapping m : svc.workspace().mappings().values()) {
            if (!m.confirmed()) {
                out.add("source '" + m.sourceId().substring(0, Math.min(8, m.sourceId().length()))
                        + "' class '" + m.className() + "' (vocab " + m.vocabVersion()
                        + ") -> " + m.canonicalKey() + " is proposed but not confirmed");
            }
        }
        return out;
    }
}
