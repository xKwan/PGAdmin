package pgweb.execution;

import org.junit.jupiter.api.Test;
import pgweb.config.Config;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Result-document and outcome rules that a real server can't easily produce on demand. */
class ResultBuilderTest {

    static final Config.Limits LIMITS = new Config.Limits(1000, 10L * 1024 * 1024, 64 * 1024, 1024 * 1024,
            Duration.ofSeconds(5), Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofMinutes(8),
            Duration.ofMinutes(60), Duration.ofSeconds(120), 3, 50, 5, Duration.ofMinutes(5));

    static ExecutionEngine.ResultBuilder builder() {
        return new ExecutionEngine.ResultBuilder(LIMITS, UUID.randomUUID(), "other");
    }

    @Test
    void aCommandWithoutACountHasNoRowCount() {
        ExecutionEngine.ResultBuilder b = builder();
        b.command(-2); // SUCCESS_NO_INFO
        b.finish("IDLE", 3, Instant.EPOCH);
        Map<String, Object> doc = b.document();
        assertThat(doc.get("kind")).isEqualTo("command");
        assertThat(((List<?>) doc.get("results")).get(0)).isEqualTo(Map.of("kind", "command"));
        assertThat(b.rowCountForAudit()).isNull();
    }

    @Test
    void noResultAtAllIsACommand() {
        ExecutionEngine.ResultBuilder b = builder();
        b.finish("OPEN", 1, Instant.EPOCH);
        assertThat(b.document()).containsEntry("kind", "command").containsEntry("transaction", "OPEN");
    }

    @Test
    void aDriverErrorWithoutServerDetailsKeepsItsMessageAndState() {
        ExecutionEngine.ResultBuilder b = builder();
        b.error(new SQLException("driver-side problem", "XX000"));
        b.finish("IDLE", 1, Instant.EPOCH);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) b.document().get("error");
        assertThat(err).containsEntry("sqlstate", "XX000").containsEntry("message", "driver-side problem").doesNotContainKey("position");
    }

    @Test
    void aStopWithoutAKnownCancelOutcomeSaysSoByOmission() {
        ExecutionEngine.ResultBuilder b = builder();
        b.stopped("cancelled", null);
        b.finish("IDLE", 1, Instant.EPOCH);
        assertThat(b.document()).containsEntry("kind", "cancelled").doesNotContainKey("cancelOutcome");
    }

    @Test
    void connectionLossIsRecognisedBySqlstateOrAClosedConnection() {
        Connection open = connection(false, false);
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "08006"), open)).isTrue();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "57P01"), open)).isTrue();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "57P03"), open)).isTrue();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "42P01"), open)).isFalse();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", (String) null), open)).isFalse();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "42P01"), connection(true, false))).isTrue();
        assertThat(ExecutionEngine.connectionBroken(new SQLException("x", "42P01"), connection(false, true))).isTrue();
    }

    @Test
    void standardConformingStringsDefaultsToOnWhenUnknown() {
        assertThat(ExecutionEngine.standardConformingStrings(connection(false, true))).isTrue();
    }

    /** A connection whose isClosed() answers {@code closed}, or throws when {@code broken}; unwrap always fails. */
    static Connection connection(boolean closed, boolean broken) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
            if (m.getName().equals("isClosed")) {
                if (broken) {
                    throw new SQLException("gone", "08003");
                }
                return closed;
            }
            if (m.getName().equals("unwrap")) {
                throw new SQLException("not a pgJDBC connection");
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }
}
