package gsb.store;

import gsb.mask.Mask;
import gsb.mask.MrleCodec;
import gsb.mask.PngCodec;
import gsb.util.Hash;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** One image workspace: event log, evidence store and derived-mask cache. */
public final class Workspace {
    public final String imageId;
    public final Path dir;
    public final EventLog log;
    public final EvidenceStore evidence;
    private final Path maskCache;

    private volatile Model model;
    private final Map<String, Mask> alignedMaskCache = new HashMap<>();

    public Workspace(String imageId, Path dir) {
        this.imageId = imageId;
        this.dir = dir;
        this.log = new EventLog(dir.resolve("events.log"));
        this.evidence = new EvidenceStore(dir.resolve("evidence"));
        this.maskCache = dir.resolve("cache");
    }

    public Model model() {
        if (model == null) {
            synchronized (this) {
                if (model == null) {
                    model = Projector.project(log.open());
                }
            }
        }
        return model;
    }

    public synchronized EventLog.Event append(String type, java.util.Map<String, Object> payload,
                                              String actor) {
        EventLog.Event event = log.append(type, java.time.Instant.now().toString(),
                actor == null ? "anonymous" : actor, payload);
        Model fresh = model == null ? new Model() : model;
        Projector.apply(fresh, event);
        Projector.markStale(fresh);
        model = fresh;
        return event;
    }

    public synchronized void reload() {
        model = Projector.project(log.open());
        alignedMaskCache.clear();
    }

    /** Load (and cache) the aligned MRLE1 derived mask for a source version. */
    public Mask alignedMask(Model.SourceVersion v) {
        Mask cached = alignedMaskCache.get(v.id);
        if (cached != null) {
            return cached;
        }
        synchronized (alignedMaskCache) {
            cached = alignedMaskCache.get(v.id);
            if (cached != null) {
                return cached;
            }
            if (v.derivedEvidence == null) {
                throw ApiException.notFound("derived mask missing for " + v.id);
            }
            byte[] bytes = evidence.read(v.derivedEvidence);
            Mask mask = MrleCodec.decode(bytes);
            alignedMaskCache.put(v.id, mask);
            return mask;
        }
    }

    public void invalidateMaskCache() {
        alignedMaskCache.clear();
    }

    public byte[] decodeRawMask(Model.SourceVersion v) {
        byte[] raw = evidence.read(v.rawEvidence);
        if ("png".equals(v.rawFormat)) {
            Mask m = PngCodec.decodeMaskPng(raw, v.nativeWidth, v.nativeHeight);
            return MrleCodec.encode(m);
        }
        return raw;
    }

    /** Verify evidence content matches the recorded fingerprint (post-reload audit). */
    public void auditEvidence(Model.SourceVersion v) {
        byte[] derived = evidence.read(v.derivedEvidence);
        String h = Hash.sha256Hex(derived);
        if (!h.equals(v.derivedFingerprint)) {
            throw new ApiException(500, "EVIDENCE_CORRUPT",
                    "derived evidence fingerprint mismatch for " + v.id)
                    .detail("recorded: " + v.derivedFingerprint)
                    .detail("actual:   " + h);
        }
    }
}
