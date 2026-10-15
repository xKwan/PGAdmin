package pgweb.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Verifies the audit hash chain (ADR-0005, threat T21) independently of the database's own functions: the canonical
 * row text comes from a fixed expression in this class, and the SHA-256 is computed here.
 * <p>
 * Detects: an edited row (hash mismatch), a deleted row (gap in {@code seq} or broken {@code prev_hash} link), and
 * rows removed from the end (chain head ahead of the last row). Connect as the reader or migrator role.
 */
public final class ChainVerifier {

    /** Outcome of a verification run. {@code firstBadSeq} is -1 when the chain is intact. */
    public record Result(boolean intact, long rows, long firstBadSeq, String problem) {
    }

    static final byte[] GENESIS = new byte[32];

    private static final String ROWS = "SELECT seq, prev_hash, row_hash, ((to_jsonb(e) - 'prev_hash') - 'row_hash')::text FROM audit.event e ORDER BY seq";
    private static final String HEAD = "SELECT seq, row_hash FROM audit.chain_head WHERE id = 1";

    private ChainVerifier() {
    }

    /** Walks the chain in one REPEATABLE READ snapshot; leaves the connection's auto-commit setting as it was. */
    public static Result verify(Connection c) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false); // a cursor (fetch size) needs a transaction in pgJDBC
        try {
            try (Statement s = c.createStatement()) {
                s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"); // one snapshot of rows and head
                s.execute("SET LOCAL TimeZone = 'UTC'");
            }
            Walk walk = new Walk();
            try (PreparedStatement ps = c.prepareStatement(ROWS)) {
                ps.setFetchSize(1000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (walk.failure == null && rs.next()) {
                        walk.accept(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getString(4));
                    }
                }
            }
            if (walk.failure != null) {
                return walk.failure;
            }
            Long headSeq = null;
            byte[] headHash = null;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(HEAD)) {
                if (rs.next()) {
                    headSeq = rs.getLong(1);
                    headHash = rs.getBytes(2);
                }
            }
            return walk.finish(headSeq, headHash);
        } finally {
            c.rollback();
            c.setAutoCommit(autoCommit);
        }
    }

    /** The chain state while walking rows in seq order. */
    static final class Walk {
        long rows;
        long expectedSeq = 1;
        byte[] prev = GENESIS;
        Result failure;

        void accept(long seq, byte[] prevHash, byte[] rowHash, String canonical) {
            rows++;
            if (seq != expectedSeq) {
                failure = new Result(false, rows, expectedSeq, "missing record(s): expected seq " + expectedSeq + ", found " + seq);
            } else if (!Arrays.equals(prevHash, prev)) {
                failure = new Result(false, rows, seq, "prev_hash does not link to the previous record at seq " + seq);
            } else if (!Arrays.equals(rowHash, hash(prevHash, canonical))) {
                failure = new Result(false, rows, seq, "record content does not match its hash at seq " + seq);
            } else {
                prev = rowHash;
                expectedSeq++;
            }
        }

        Result finish(Long headSeq, byte[] headHash) {
            long last = expectedSeq - 1;
            if (headSeq == null) {
                return new Result(false, rows, -1, "chain head missing");
            }
            if (headSeq != last || !Arrays.equals(headHash, prev)) {
                return new Result(false, rows, expectedSeq,
                        "chain head (seq " + headSeq + ") does not match the last record (seq " + last + "): records removed from the end?");
            }
            return new Result(true, rows, -1, null);
        }
    }

    static byte[] hash(byte[] prevHash, String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash);
            md.update(canonical.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
