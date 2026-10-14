package spike.s3.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Random;

/**
 * Supplies test parameters:
 * <ul>
 *   <li>{@link EphemeralPostgres}: one throwaway cluster per Maven run, shared by all test classes and closed (and
 *       checked for leftovers) when the run ends;</li>
 *   <li>{@link Random}: seeded from {@code s3.seed} and the test's unique id, so a reported seed replays the
 *       same scenario parameters;</li>
 *   <li>{@link Findings}: observations written to {@code target/s3-findings.jsonl} for the spike report.</li>
 * </ul>
 */
public final class SpikeExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SpikeExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        Class<?> t = pc.getParameter().getType();
        return t == EphemeralPostgres.class || t == Random.class || t == Findings.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        Class<?> t = pc.getParameter().getType();
        if (t == Random.class) {
            return new Random(seed() * 31 + ec.getUniqueId().hashCode());
        }
        if (t == Findings.class) {
            return Findings.instance();
        }
        ExtensionContext.Store store = ec.getRoot().getStore(NS);
        return store.computeIfAbsent("cluster", k -> new ClusterResource(), ClusterResource.class).pg;
    }

    public static long seed() {
        String s = System.getProperty("s3.seed", "0");
        return s.isBlank() ? 0L : Long.parseLong(s.trim());
    }

    /** Closed by JUnit at the end of the run. Teardown leftovers fail loudly. */
    static final class ClusterResource implements AutoCloseable {
        final EphemeralPostgres pg;

        ClusterResource() {
            try {
                pg = EphemeralPostgres.start(System.getProperty("s3.pgBin"));
                Findings.instance().record("environment", "server_version", pg.serverVersion());
                Findings.instance().record("environment", "pg_bin", pg.pgBin().toString());
            } catch (Exception e) {
                throw new IllegalStateException("could not start the throwaway cluster", e);
            }
        }

        @Override
        public void close() {
            pg.close();
            if (!pg.isGone()) {
                throw new IllegalStateException("teardown left the cluster behind: " + pg.root());
            }
            Findings.instance().record("environment", "teardown", "directory removed and port free");
        }
    }
}
