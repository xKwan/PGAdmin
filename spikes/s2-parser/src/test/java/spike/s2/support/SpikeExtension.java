package spike.s2.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Random;

/**
 * Supplies test parameters:
 * <ul>
 *   <li>{@link EphemeralPostgres}: one throwaway cluster per Maven run, shared by all test classes and closed (and
 *       checked for leftovers) when the run ends;</li>
 *   <li>{@link Random}: seeded from {@code s2.seed} and the test's unique id, so a reported seed replays the
 *       same scenario parameters;</li>
 *   <li>{@link Findings}: observations written to {@code target/s2-findings.jsonl} for the spike report;</li>
 *   <li>{@link PgQuery}: the libpg_query DLL (path from {@code s2.dll}), loaded once per run.</li>
 * </ul>
 */
public final class SpikeExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SpikeExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        Class<?> t = pc.getParameter().getType();
        return t == EphemeralPostgres.class || t == Random.class || t == Findings.class || t == PgQuery.class;
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
        if (t == PgQuery.class) {
            return ec.getRoot().getStore(NS).computeIfAbsent("pgquery",
                    k -> PgQuery.load(java.nio.file.Paths.get(System.getProperty("s2.dll", "build/pg_query.dll"))), PgQuery.class);
        }
        ExtensionContext.Store store = ec.getRoot().getStore(NS);
        return store.computeIfAbsent("cluster", k -> new ClusterResource(), ClusterResource.class).pg;
    }

    public static long seed() {
        String s = System.getProperty("s2.seed", "0");
        return s.isBlank() ? 0L : Long.parseLong(s.trim());
    }

    /** Closed by JUnit at the end of the run. Teardown leftovers fail loudly. */
    static final class ClusterResource implements AutoCloseable {
        final EphemeralPostgres pg;

        ClusterResource() {
            try {
                pg = EphemeralPostgres.start(System.getProperty("s2.pgBin"));
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
