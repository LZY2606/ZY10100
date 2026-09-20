package gsb.store;

import gsb.json.Json;
import gsb.util.Hash;
import gsb.util.Hex;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained event log (JSON Lines).
 *
 * <p>Each physical line is:
 * {@code {"seq":N,"type":T,"at":"...","actor":"...","payload":{...},"prev":"hex","hash":"hex"}}
 *
 * <p>{@code hash = sha256(prev || "\n" || canonicalWithoutHash)}. Replay
 * verifies the full chain; any broken link fails fast with the offending seq.
 */
public final class EventLog {
    public static final class Event {
        public final long seq;
        public final String type;
        public final String at;
        public final String actor;
        public final Map<String, Object> payload;
        public final String prev;
        public final String hash;

        public Event(long seq, String type, String at, String actor,
                     Map<String, Object> payload, String prev, String hash) {
            this.seq = seq;
            this.type = type;
            this.at = at;
            this.actor = actor;
            this.payload = payload;
            this.prev = prev;
            this.hash = hash;
        }

        Map<String, Object> toLine() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("type", type);
            m.put("at", at);
            m.put("actor", actor);
            m.put("payload", payload);
            m.put("prev", prev);
            m.put("hash", hash);
            return m;
        }
    }

    public static final class ChainBrokenException extends IllegalStateException {
        public final long seq;

        public ChainBrokenException(long seq, String message) {
            super(message);
            this.seq = seq;
        }
    }

    private final Path file;
    private final Object lock = new Object();
    private long nextSeq = 1;
    private String lastHash = "0".repeat(64);

    public EventLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Read and verify the whole chain. Returns events; positions the cursor. */
    public List<Event> open() {
        synchronized (lock) {
            List<Event> events = new ArrayList<>();
            if (!Files.exists(file)) {
                nextSeq = 1;
                lastHash = "0".repeat(64);
                return events;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String expectedPrev = "0".repeat(64);
            long expectedSeq = 1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> raw;
                try {
                    raw = Json.parseObject(line);
                } catch (RuntimeException e) {
                    throw new ChainBrokenException(expectedSeq,
                            "event log line " + (i + 1) + " is not valid JSON: " + e.getMessage());
                }
                long seq = Json.lng(raw, "seq", -1);
                String prev = Json.str(raw, "prev");
                String hash = Json.str(raw, "hash");
                if (seq != expectedSeq) {
                    throw new ChainBrokenException(seq,
                            "event log seq gap: expected " + expectedSeq + " but found " + seq
                                    + " (line " + (i + 1) + ")");
                }
                if (!expectedPrev.equals(prev)) {
                    throw new ChainBrokenException(seq,
                            "event log broken chain at seq " + seq + ": prev does not match");
                }
                Map<String, Object> canonical = new LinkedHashMap<>(raw);
                canonical.remove("hash");
                String recomputed = Hash.sha256Hex(
                        expectedPrev + "\n" + Json.write(canonical));
                if (!recomputed.equals(hash)) {
                    throw new ChainBrokenException(seq,
                            "event log payload tampered with at seq " + seq
                                    + " (recorded hash mismatch)");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) raw.get("payload");
                events.add(new Event(seq, Json.str(raw, "type"), Json.str(raw, "at"),
                        Json.str(raw, "actor"), payload, prev, hash));
                expectedPrev = hash;
                expectedSeq++;
            }
            nextSeq = expectedSeq;
            lastHash = expectedPrev;
            return events;
        }
    }

    /** Append an event; returns the stored, chained event. */
    public Event append(String type, String at, String actor, Map<String, Object> payload) {
        synchronized (lock) {
            long seq = nextSeq;
            String prev = lastHash;
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("seq", seq);
            canonical.put("type", type);
            canonical.put("at", at);
            canonical.put("actor", actor == null ? "anonymous" : actor);
            canonical.put("payload", payload);
            canonical.put("prev", prev);
            String hash = Hash.sha256Hex(prev + "\n" + Json.write(canonical));
            Event event = new Event(seq, type, at, actor == null ? "anonymous" : actor,
                    payload, prev, hash);
            String line = Json.write(event.toLine()) + "\n";
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append event log", e);
            }
            nextSeq++;
            lastHash = hash;
            return event;
        }
    }

    public long nextSeq() {
        synchronized (lock) {
            return nextSeq;
        }
    }

    public String lastHash() {
        synchronized (lock) {
            return lastHash;
        }
    }

    /**
     * Recovery tool: truncate everything after the last verified event.
     * Returns the number of removed lines. Creates a .corrupt backup first.
     */
    public int truncateAfterLastValid(Path backup) throws IOException {
        synchronized (lock) {
            List<String> lines = Files.exists(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8)
                    : List.of();
            String expectedPrev = "0".repeat(64);
            long expectedSeq = 1;
            int valid = 0;
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> raw = Json.parseObject(line);
                    long seq = Json.lng(raw, "seq", -1);
                    String prev = Json.str(raw, "prev");
                    String hash = Json.str(raw, "hash");
                    Map<String, Object> canonical = new LinkedHashMap<>(raw);
                    canonical.remove("hash");
                    String recomputed = Hash.sha256Hex(
                            expectedPrev + "\n" + Json.write(canonical));
                    if (seq != expectedSeq || !expectedPrev.equals(prev)
                            || !recomputed.equals(hash)) {
                        break;
                    }
                    expectedPrev = hash;
                    expectedSeq++;
                    valid++;
                } catch (RuntimeException e) {
                    break;
                }
            }
            if (valid == lines.size()) {
                return 0;
            }
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
                int idx = 0;
                for (String line : lines) {
                    if (idx >= valid) {
                        break;
                    }
                    if (!line.isBlank()) {
                        w.write(line);
                        w.newLine();
                    }
                    idx++;
                }
            }
            nextSeq = expectedSeq;
            lastHash = expectedPrev;
            return lines.size() - valid;
        }
    }
}
