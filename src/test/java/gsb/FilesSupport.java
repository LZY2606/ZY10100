package gsb;

import java.nio.file.Files;
import java.nio.file.Path;

final class FilesSupport {
    private FilesSupport() {}

    static Path tempDir() throws Exception {
        Path p = Files.createTempDirectory("gsb-test-");
        p.toFile().deleteOnExit();
        return p;
    }
}
