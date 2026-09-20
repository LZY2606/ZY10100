package gsb.service;

import java.util.List;

/** Result of re-importing an export bundle. */
public record ImportReport(
        boolean accepted,
        String workspaceId,
        long sourcesRestored,
        long eventsRestored,
        long regionPixels,
        String boundaryHash,
        List<String> problems,
        List<String> warnings) {
}
