package gsb;

import gsb.service.AppKernel;
import gsb.service.WorkspaceService;
import gsb.storage.WorkspaceStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class TestEnv {
    private TestEnv() {}

    public static Path tempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("gsb-test-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static WorkspaceService newWorkspace(Path dir) {
        WorkspaceStore store = new WorkspaceStore(dir);
        WorkspaceService svc = new WorkspaceService(store,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        svc.create("ws", "Test WS", DomainFixtures.imageA(), DomainFixtures.geom());
        return svc;
    }

    public static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
