package gsb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import gsb.crypto.Hashes;
import gsb.model.ClassMapping;
import gsb.model.DecisionEvent;
import gsb.model.ImageRef;
import gsb.model.ReleaseSnapshot;
import gsb.model.Source;
import gsb.model.Workspace;
import gsb.storage.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP export / re-import. The bundle contains compressed RLE masks, a source index and the full
 * decision history. Re-import rebuilds derived consensus and asserts region area + boundary
 * hash equality, so a tampered or lossy round trip is rejected.
 */
public final class ExportService {

    /** Build an export bundle (optionally pinned to a specific release snapshot). */
    public byte[] exportBundle(WorkspaceService svc, ReleaseSnapshot release) {
        Workspace ws = svc.workspace();
        ConsensusBuilder builder = new ConsensusBuilder();
        BuiltRaster raster = builder.build(svc);

        Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (Source s : ws.sources().values()) {
            blobs.put(s.maskBlobSha256(), svc.store().blobs().get(s.maskBlobSha256()));
            blobs.put(s.confidenceBlobSha256(), svc.store().blobs().get(s.confidenceBlobSha256()));
        }

        ExportBundle bundle = new ExportBundle(
                ExportBundle.FORMAT_VERSION,
                ws.ruleSetVersion(),
                ws.image().fingerprintSha256(),
                ws.canonicalGeometry(),
                List.copyOf(ws.sources().values()),
                List.copyOf(ws.mappings().values()),
                List.copyOf(ws.events()),
                blobs,
                raster.boundaryHash(),
                raster.regionPixels(),
                release == null ? null : release.createdBy(),
                Instant.now(svc.clock()));
        return zip(bundle);
    }

    public byte[] zip(ExportBundle bundle) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("formatVersion", bundle.formatVersion());
                manifest.put("ruleSetVersion", bundle.ruleSetVersion());
                manifest.put("imageFingerprint", bundle.imageFingerprint());
                manifest.put("canonicalGeometry", bundle.canonicalGeometry());
                manifest.put("sources", bundle.sources());
                manifest.put("mappings", bundle.mappings());
                manifest.put("decisionHistory", bundle.decisionHistory());
                manifest.put("blobIndex", new ArrayList<>(bundle.blobs().keySet()));
                manifest.put("consensusBoundaryHash", bundle.consensusBoundaryHash());
                manifest.put("consensusRegionPixels", bundle.consensusRegionPixels());
                manifest.put("releasedBy", bundle.releasedBy());
                manifest.put("exportedAt", bundle.exportedAt());

                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(Json.write(manifest));
                zip.closeEntry();

                for (Map.Entry<String, byte[]> e : bundle.blobs().entrySet()) {
                    zip.putNextEntry(new ZipEntry("blobs/" + e.getKey() + ".bin"));
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("export failed", e);
        }
    }

    /** Parse a bundle into its typed form, verifying every blob hash. */
    public ExportBundle unzip(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            Map<String, Object> manifest = null;
            Map<String, byte[]> blobs = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                if (entry.getName().equals("manifest.json")) {
                    manifest = Json.MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
                } else if (entry.getName().startsWith("blobs/")) {
                    String hash = entry.getName().substring("blobs/".length(),
                            entry.getName().length() - ".bin".length());
                    blobs.put(hash, content);
                }
            }
            if (manifest == null) {
                throw ValidationException.of("BUNDLE_CORRUPT", "manifest.json missing");
            }
            return convertManifest(manifest, blobs);
        } catch (IOException e) {
            throw ValidationException.of("BUNDLE_CORRUPT", "unreadable export bundle: " + e.getMessage());
        }
    }

    private ExportBundle convertManifest(Map<String, Object> manifest, Map<String, byte[]> blobs) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : blobs.entrySet()) {
            String actual = Hashes.sha256Hex(e.getValue());
            if (!actual.equals(e.getKey())) {
                problems.add("blob " + e.getKey().substring(0, 12) + ".. hash mismatch ("
                        + actual.substring(0, 12) + "..)");
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("BUNDLE_TAMPERED", problems);
        }
        @SuppressWarnings("unchecked")
        List<String> blobIndex = (List<String>) manifest.get("blobIndex");
        for (String expected : blobIndex) {
            if (!blobs.containsKey(expected)) {
                throw ValidationException.of("BUNDLE_INCOMPLETE",
                        "missing blob " + expected.substring(0, 12) + "..");
            }
        }
        gsb.geom.Geometry geometry = Json.MAPPER.convertValue(
                manifest.get("canonicalGeometry"), gsb.geom.Geometry.class);
        List<Source> sources = Json.MAPPER.convertValue(manifest.get("sources"),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class, Source.class));
        List<ClassMapping> mappings = Json.MAPPER.convertValue(manifest.get("mappings"),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class, ClassMapping.class));
        List<DecisionEvent> events = Json.MAPPER.convertValue(manifest.get("decisionHistory"),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class, DecisionEvent.class));
        return new ExportBundle(
                (String) manifest.get("formatVersion"),
                (String) manifest.get("ruleSetVersion"),
                (String) manifest.get("imageFingerprint"),
                geometry,
                sources,
                mappings,
                events,
                blobs,
                (String) manifest.get("consensusBoundaryHash"),
                ((Number) manifest.get("consensusRegionPixels")).longValue(),
                (String) manifest.get("releasedBy"),
                manifest.get("exportedAt") == null ? null
                        : Instant.parse((String) manifest.get("exportedAt")));
    }

    /**
     * Re-import into a fresh workspace id and verify derived invariants (region area + boundary
     * hash). Input evidence is restored verbatim; only derived consensus is recomputed.
     */
    public ImportReport reimport(WorkspaceService target, String newWorkspaceId,
                                 String newWorkspaceName, byte[] bundleData) {
        ExportBundle bundle = unzip(bundleData);
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!ExportBundle.FORMAT_VERSION.equals(bundle.formatVersion())) {
            problems.add("unsupported export format version " + bundle.formatVersion());
        }
        if (!Workspace.RULESET_VERSION.equals(bundle.ruleSetVersion())) {
            warnings.add("bundle ruleSetVersion " + bundle.ruleSetVersion()
                    + " differs from running " + Workspace.RULESET_VERSION);
        }
        if (!problems.isEmpty()) {
            return new ImportReport(false, null, 0, 0, 0, null, problems, warnings);
        }

        // Restore immutable evidence.
        for (byte[] blob : bundle.blobs().values()) {
            target.store().blobs().put(blob); // content-addressed; verifies on read later
        }
        ImageRef image = new ImageRef(newWorkspaceId, bundle.imageFingerprint());
        target.create(newWorkspaceId, newWorkspaceName, image, bundle.canonicalGeometry());

        for (Source s : bundle.sources()) {
            if (target.store().blobs().exists(s.maskBlobSha256())
                    && target.store().blobs().exists(s.confidenceBlobSha256())) {
                target.addSource(s);
            } else {
                problems.add("restored source references missing blob");
            }
        }
        for (ClassMapping m : bundle.mappings()) {
            target.putMapping(m);
        }
        // Replay decision history verbatim (events are immutable evidence too).
        for (DecisionEvent e : bundle.decisionHistory()) {
            target.appendEvent(e);
        }

        BuiltRaster rebuilt = new ConsensusBuilder().build(target);
        if (rebuilt.regionPixels() != bundle.consensusRegionPixels()) {
            problems.add("region area mismatch after reimport: " + rebuilt.regionPixels()
                    + " vs exported " + bundle.consensusRegionPixels());
        }
        if (!rebuilt.boundaryHash().equals(bundle.consensusBoundaryHash())) {
            problems.add("boundary hash mismatch after reimport: " + rebuilt.boundaryHash()
                    + " vs exported " + bundle.consensusBoundaryHash());
        }
        boolean accepted = problems.isEmpty();
        return new ImportReport(accepted, newWorkspaceId, bundle.sources().size(),
                bundle.decisionHistory().size(), rebuilt.regionPixels(),
                rebuilt.boundaryHash(), problems, warnings);
    }
}
