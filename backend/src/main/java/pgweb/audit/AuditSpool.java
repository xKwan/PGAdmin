package pgweb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Local spool for audit records that could not be written to the audit database (docs/audit-policy.md §5): one JSON
 * object per line in {@code audit-spool.jsonl}, each line chained to the previous one by SHA-256 so truncation or
 * edits are detectable. The spool is <b>not</b> an audit record; it only carries records until they are replayed.
 * Protect the folder with NTFS ACLs (service account write, auditors read).
 */
public final class AuditSpool {

    static final String FILE = "audit-spool.jsonl";
    private static final String GENESIS = "0".repeat(64);
    private static final String SPOOL_HASH = "spoolHash";
    private static final String SPOOL_PREV = "spoolPrev";

    private final Path dir;
    private final Path spoolFile;
    private final ObjectMapper mapper;
    private final Clock clock;
    private String lastHash;
    private long count;

    public AuditSpool(Path dir, ObjectMapper mapper, Clock clock) {
        this.dir = dir;
        this.spoolFile = dir.resolve(FILE);
        this.mapper = mapper;
        this.clock = clock;
        try {
            Files.createDirectories(dir);
            List<JsonNode> existing = readVerified();
            count = existing.size();
            lastHash = existing.isEmpty() ? GENESIS : existing.get(existing.size() - 1).path(SPOOL_HASH).asText();
        } catch (IOException e) {
            throw new UncheckedIOException("audit spool folder unusable: " + dir, e);
        }
    }

    /** Appends one record. Synchronised: the chain needs a total order. */
    public synchronized void append(ObjectNode record) throws IOException {
        ObjectNode line = record.deepCopy();
        line.put(SPOOL_PREV, lastHash);
        line.remove(SPOOL_HASH);
        String hash = sha256(lastHash + mapper.writeValueAsString(line));
        line.put(SPOOL_HASH, hash);
        Files.writeString(spoolFile, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        lastHash = hash;
        count++;
    }

    public synchronized long size() {
        return count;
    }

    /** Spooled records in order, after verifying the chain. Throws if the spool was altered. */
    public synchronized List<JsonNode> pending() throws IOException {
        return readVerified();
    }

    /**
     * After every pending record was written to the database: keeps the file as
     * {@code audit-spool-replayed-<timestamp>.jsonl} for forensics and starts a new chain.
     */
    public synchronized Path markReplayed() throws IOException {
        if (!Files.exists(spoolFile)) {
            return null;
        }
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC).format(clock.instant());
        Path done = dir.resolve("audit-spool-replayed-" + ts + ".jsonl");
        Files.move(spoolFile, done, StandardCopyOption.ATOMIC_MOVE);
        lastHash = GENESIS;
        count = 0;
        return done;
    }

    private List<JsonNode> readVerified() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(spoolFile)) {
            return out;
        }
        String prev = GENESIS;
        int n = 0;
        for (String raw : Files.readAllLines(spoolFile, StandardCharsets.UTF_8)) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            ObjectNode line = (ObjectNode) mapper.readTree(raw);
            String hash = line.path(SPOOL_HASH).asText();
            if (!prev.equals(line.path(SPOOL_PREV).asText())) {
                throw new IOException("audit spool chain broken at line " + n + " (record removed or reordered)");
            }
            ObjectNode body = line.deepCopy();
            body.remove(SPOOL_HASH);
            if (!hash.equals(sha256(prev + mapper.writeValueAsString(body)))) {
                throw new IOException("audit spool chain broken at line " + n + " (record altered)");
            }
            out.add(line);
            prev = hash;
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
