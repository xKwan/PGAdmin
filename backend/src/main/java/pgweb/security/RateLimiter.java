package pgweb.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter per key (for example, failed DB-session opens per Windows SID: SEC-11, T11). When a key has
 * {@code limit} failures inside {@code window}, it is blocked until the oldest of them leaves the window.
 * In memory only: the service is a single instance (D12), and a restart clearing the counters is acceptable.
 */
public final class RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public RateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** True if {@code key} is currently blocked. */
    public boolean blocked(String key) {
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= limit;
        }
    }

    /** Records a failure for {@code key}. */
    public void recordFailure(String key) {
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
    }

    /** Clears the failures of {@code key} (after a success). */
    public void reset(String key) {
        events.remove(key);
    }

    /** How long until {@code key} is unblocked; zero if not blocked. */
    public Duration retryAfter(String key) {
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return Duration.ZERO;
        }
        synchronized (q) {
            prune(q);
            if (q.size() < limit) {
                return Duration.ZERO;
            }
            Duration d = Duration.between(clock.instant(), q.peekFirst().plus(window));
            return d.isNegative() ? Duration.ZERO : d;
        }
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
    }
}
