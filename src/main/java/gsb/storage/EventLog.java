package gsb.storage;

import gsb.crypto.Hashes;
import gsb.model.DecisionEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only decision event log. Each physical line is
 * {@code <sha256-prefix16>:<json>}. On recovery, the last line whose checksum or JSON is invalid
 * (a torn append from a crash mid-write) is truncated; everything before it is replayed.
 */
public final class EventLog {
    private final Path file;

    public EventLog(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open event log " + file, e);
        }
    }

    public synchronized void append(DecisionEvent event) {
        String json = Json.writeString(event);
        String line = Hashes.sha256Hex(json).substring(0, 16) + ":" + json;
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("event append failed", e);
        }
    }

    /** Replay all intact lines; truncate and report any torn tail. */
    public synchronized Recovered recover() {
        List<DecisionEvent> events = new ArrayList<>();
        long validBytes = 0;
        int dropped = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                int consumed = raw.getBytes(StandardCharsets.UTF_8).length
                        + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
                String candidate = raw;
                if (isIntact(candidate)) {
                    events.add(parse(candidate));
                    validBytes += consumed;
                } else {
                    dropped++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("event replay failed", e);
        }
        if (dropped > 0) {
            truncateToValidPrefix(validBytes);
        }
        return new Recovered(events, dropped);
    }

    private boolean isIntact(String line) {
        int colon = line.indexOf(':');
        if (colon != 16) {
            return false;
        }
        String checksum = line.substring(0, 16);
        String json = line.substring(17);
        if (!Hashes.sha256Hex(json).substring(0, 16).equals(checksum)) {
            return false;
        }
        try {
            Json.read(json.getBytes(StandardCharsets.UTF_8), DecisionEvent.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private DecisionEvent parse(String line) {
        return Json.read(line.substring(17).getBytes(StandardCharsets.UTF_8), DecisionEvent.class);
    }

    private void truncateToValidPrefix(long validBytes) {
        Path repaired = file.resolveSibling(file.getFileName() + ".repair");
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             BufferedWriter out = Files.newBufferedWriter(repaired, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            String raw;
            while (written < validBytes && (raw = in.readLine()) != null) {
                if (isIntact(raw)) {
                    out.write(raw);
                    out.newLine();
                    written += raw.getBytes(StandardCharsets.UTF_8).length
                            + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("event log repair failed", e);
        }
        try {
            Files.move(repaired, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(repaired, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new UncheckedIOException("event log repair move failed", e2);
            }
        }
    }

    public record Recovered(List<DecisionEvent> events, int droppedLines) {}
}
