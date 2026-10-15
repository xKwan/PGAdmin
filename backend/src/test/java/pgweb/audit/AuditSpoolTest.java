package pgweb.audit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pgweb.config.Secret;
import pgweb.testsupport.MutableClock;
import pgweb.testsupport.Seeds;
import pgweb.web.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSpoolTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T01:00:00Z"));

    private ObjectNode record(Random r) {
        ObjectNode n = Json.mapper().createObjectNode();
        n.put("eventId", java.util.UUID.randomUUID().toString());
        n.put("eventType", "app_login");
        n.put("n", r.nextInt());
        n.put("text", "quote\" é ' \\ " + r.nextInt(100));
        return n;
    }

    @Test
    void appendsSurviveReopeningAndVerify() throws Exception {
        Random r = Seeds.random("spool-append");
        int n = 1 + r.nextInt(20);
        AuditSpool s = new AuditSpool(dir, Json.mapper(), clock);
        for (int i = 0; i < n; i++) {
            s.append(record(r));
        }
        assertThat(s.size()).isEqualTo(n);
        Files.writeString(dir.resolve(AuditSpool.FILE), System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND); // blank lines are ignored
        AuditSpool reopened = new AuditSpool(dir, Json.mapper(), clock);
        assertThat(reopened.size()).isEqualTo(n);
        reopened.append(record(r));
        assertThat(reopened.pending()).hasSize(n + 1);
    }

    @Test
    void editedRemovedOrReorderedLinesAreDetected() throws Exception {
        Random r = Seeds.random("spool-tamper");
        for (String fault : List.of("edit", "remove", "swap")) {
            Path d = Files.createDirectories(dir.resolve(fault));
            AuditSpool s = new AuditSpool(d, Json.mapper(), clock);
            for (int i = 0; i < 4; i++) {
                s.append(record(r));
            }
            Path f = d.resolve(AuditSpool.FILE);
            List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
            switch (fault) {
                case "edit" -> lines.set(1, lines.get(1).replace("app_login", "app_logout"));
                case "remove" -> lines.remove(1);
                default -> {
                    String t = lines.get(1);
                    lines.set(1, lines.get(2));
                    lines.set(2, t);
                }
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
            assertThatThrownBy(s::pending).as(fault).hasMessageContaining("audit spool chain broken");
            assertThatThrownBy(() -> new AuditSpool(d, Json.mapper(), clock)).as(fault).isInstanceOf(java.io.UncheckedIOException.class);
        }
    }

    @Test
    void markReplayedKeepsTheFileAndRestartsTheChain() throws Exception {
        AuditSpool s = new AuditSpool(dir, Json.mapper(), clock);
        assertThat(s.markReplayed()).isNull();
        s.append(record(Seeds.random("spool-replayed")));
        Path done = s.markReplayed();
        assertThat(done).exists();
        assertThat(done.getFileName().toString()).startsWith("audit-spool-replayed-");
        assertThat(s.size()).isZero();
        assertThat(s.pending()).isEmpty();
        s.append(record(Seeds.random("spool-replayed-2")));
        assertThat(new AuditSpool(dir, Json.mapper(), clock).pending()).hasSize(1);
    }

    @Test
    void hmacsAreKeyedAndStable() {
        AuditService a = new AuditService(null, new AuditSpool(dir, Json.mapper(), clock), AuditService.FailMode.CLOSED,
                new Secret("k".repeat(32).getBytes(StandardCharsets.UTF_8)), Json.mapper(), false);
        AuditService b = new AuditService(null, new AuditSpool(dir.resolve("b"), Json.mapper(), clock), AuditService.FailMode.CLOSED,
                new Secret("x".repeat(32).getBytes(StandardCharsets.UTF_8)), Json.mapper(), false);
        assertThat(a.sqlHmac("SELECT 1")).matches("[0-9a-f]{64}").isEqualTo(a.sqlHmac("SELECT 1")).isNotEqualTo(b.sqlHmac("SELECT 1"));
        assertThat(a.sessionHash("ABC")).hasSize(32).isNotEqualTo(a.sessionHash("ABD")).isNotEqualTo(b.sessionHash("ABC"));
        assertThat(a.sessionHash("ABC")).isNotEqualTo(a.sqlHmac("ABC").substring(0, 32));
        a.close();
        b.close();
    }

    @Test
    void eventTypesAreValidated() {
        assertThatThrownBy(() -> AuditEvent.of("Bad-Type")).isInstanceOf(IllegalArgumentException.class);
        AuditEvent e = AuditEvent.of("app_login").detail("k", null).detail("x", 1);
        assertThat(e.details()).containsOnlyKeys("x");
        assertThat(e.type()).isEqualTo("app_login");
    }
}
