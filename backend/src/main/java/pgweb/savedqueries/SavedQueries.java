package pgweb.savedqueries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import pgweb.audit.AuditEvent;
import pgweb.audit.AuditService;
import pgweb.security.UserContext;
import pgweb.web.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Saved queries stored on the server (admin Phase 11, decision A16). Each Windows account (by SID) has its own list;
 * nobody else can read, change or list it, and there is no sharing.
 * <ul>
 *   <li>Stored as one JSON file per account under {@code <workDir>/saved-queries/}, named by a hash of the SID, written
 *       to a temporary file and moved into place. The folder inherits the work folder's ACL (service account only).</li>
 *   <li>Bounded: {@link #MAX_QUERIES} queries, {@link #MAX_TEXT} characters each, {@link #MAX_NAME} for a name.</li>
 *   <li>Saving and deleting are audited first (fail-closed) with the query's ID, name length and the statement HMAC,
 *       never the text. Saving runs nothing.</li>
 *   <li>Off unless {@code savedQueries.enabled=true}: the text is data at rest on the app server (T42).</li>
 * </ul>
 */
public final class SavedQueries {

    static final int MAX_QUERIES = 200;
    static final int MAX_TEXT = 100_000;
    static final int MAX_NAME = 100;
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() { };
    private static final String NAME = "name";
    private static final String TEXT = "text";
    private static final String UPDATED = "updatedAt";

    private final Path dir;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SavedQueries(Path dir, boolean enabled, AuditService audit, ObjectMapper mapper, Clock clock) {
        this.dir = dir;
        this.enabled = enabled;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Name, ID, size and time of each of the account's queries, newest first; no text. */
    public Map<String, Object> list(UserContext user) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock(user)) {
            for (Map<String, Object> q : read(user)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", q.get("id"));
                m.put(NAME, q.get(NAME));
                m.put("length", ((String) q.get(TEXT)).length());
                m.put(UPDATED, q.get(UPDATED));
                out.add(m);
            }
        }
        out.sort((a, b) -> String.valueOf(b.get(UPDATED)).compareTo(String.valueOf(a.get(UPDATED))));
        return Map.of("queries", out, "max", MAX_QUERIES);
    }

    public Map<String, Object> get(UserContext user, String id) {
        requireId(id);
        synchronized (lock(user)) {
            return read(user).stream().filter(q -> id.equals(q.get("id"))).findFirst().orElseThrow(ApiException::notFound);
        }
    }

    /** Creates a query, or replaces the one with {@code id}. Returns it without its text. */
    public Map<String, Object> save(UserContext user, String id, String name, String text) {
        requireEnabled();
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME || n.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw ApiException.badRequest("INVALID_NAME", "Give the query a name of 1 to " + MAX_NAME + " characters, on one line.");
        }
        if (text == null || text.isBlank() || text.length() > MAX_TEXT || text.indexOf('\0') >= 0) {
            throw ApiException.badRequest("INVALID_TEXT", "The query must have text, of at most " + MAX_TEXT + " characters.");
        }
        if (id != null) {
            requireId(id);
        }
        synchronized (lock(user)) {
            List<Map<String, Object>> all = read(user);
            Map<String, Object> q = id == null ? null : all.stream().filter(x -> id.equals(x.get("id"))).findFirst().orElseThrow(ApiException::notFound);
            boolean nameTaken = all.stream().anyMatch(x -> n.equalsIgnoreCase((String) x.get(NAME)) && (q == null || x != q));
            if (nameTaken) {
                throw ApiException.conflict("NAME_TAKEN", "You already have a saved query with this name.");
            }
            if (q == null && all.size() >= MAX_QUERIES) {
                throw ApiException.conflict("TOO_MANY", "You have " + MAX_QUERIES + " saved queries, the most there can be. Delete some first.");
            }
            String now = clock.instant().toString();
            String qid = id == null ? UUID.randomUUID().toString() : id;
            audit.require(AuditEvent.of("saved_query_saved").user(user).detail("id", qid).detail("created", q == null)
                    .detail("nameLength", n.length()).detail("textLength", text.length()).detail("sqlHmac", audit.sqlHmac(text)));
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("id", qid);
            saved.put(NAME, n);
            saved.put(TEXT, text);
            saved.put("createdAt", q == null ? now : q.get("createdAt"));
            saved.put(UPDATED, now);
            if (q == null) {
                all.add(saved);
            } else {
                all.set(all.indexOf(q), saved);
            }
            write(user, all);
            Map<String, Object> out = new LinkedHashMap<>(saved);
            out.remove(TEXT);
            return out;
        }
    }

    public void delete(UserContext user, String id) {
        requireEnabled();
        requireId(id);
        synchronized (lock(user)) {
            List<Map<String, Object>> all = read(user);
            if (!all.removeIf(q -> id.equals(q.get("id")))) {
                throw ApiException.notFound();
            }
            audit.require(AuditEvent.of("saved_query_deleted").user(user).detail("id", id));
            write(user, all);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw ApiException.notFound();
        }
    }

    private static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.notFound();
        }
    }

    private Object lock(UserContext user) {
        requireEnabled();
        return locks.computeIfAbsent(user.sid(), k -> new Object());
    }

    /** One file per account, named by the SHA-256 of its SID (no account names on disk). */
    Path file(UserContext user) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(user.sid().getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(h) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> read(UserContext user) {
        Path f = file(user);
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(mapper.readValue(f.toFile(), LIST));
        } catch (IOException e) {
            throw new ApiException(500, "SAVED_QUERIES_UNREADABLE", "Your saved queries could not be read. Ask the administrator.");
        }
    }

    private void write(UserContext user, List<Map<String, Object>> all) {
        Path f = file(user);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "save-", ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), all);
                try {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ApiException(500, "SAVED_QUERIES_UNWRITABLE", "Your query could not be saved. Ask the administrator.");
        }
    }
}
