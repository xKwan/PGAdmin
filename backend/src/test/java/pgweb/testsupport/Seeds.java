package pgweb.testsupport;

import java.util.Random;

/**
 * Seeded randomness for tests (delivery workflow: every loop run uses a new seed, recorded in the loop report).
 * The seed comes from {@code -Dpgweb.test.seed}; each test derives its own stream from its name, so adding a test
 * doesn't change the values another test sees.
 */
public final class Seeds {

    private Seeds() {
    }

    public static long seed() {
        String s = System.getProperty("pgweb.test.seed", "0");
        try {
            return s.isBlank() ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static Random random(String name) {
        return new Random(seed() * 1_000_003L + name.hashCode());
    }
}
