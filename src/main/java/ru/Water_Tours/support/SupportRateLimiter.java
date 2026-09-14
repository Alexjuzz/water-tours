package ru.Water_Tours.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, in-memory submission limits for the public question form.
 *
 * Three separate buckets, because none of them is sufficient alone:
 * <ul>
 *   <li><b>per contact</b> - the only key that is stable for a real customer;</li>
 *   <li><b>per client address</b> - catches one script rotating contacts;</li>
 *   <li><b>global</b> - the backstop only. It used to be the effective limit, because the
 *       per-address bucket collapsed into a single key while the backend saw nginx rather than
 *       the client; at 40/hour that let one script silence the form for every real customer.
 *       Now that the client address is visible ({@code server.forward-headers-strategy=native})
 *       the per-address bucket does the work and this cap is set far higher, as a bound on the
 *       owner's inbox rather than as the thing a stranger trips.</li>
 * </ul>
 *
 * Storage is bounded twice over: expired windows are pruned on every write, and past
 * {@code MAX_KEYS} live keys new keys are refused instead of being added, so a key-rotating
 * flood cannot grow the map. Refusing is the safe direction - it costs a stranger a retry, it
 * never loses a stored inquiry, and it never consumes unbounded memory.
 */
@Component
public class SupportRateLimiter {

    static final int MAX_KEYS = 5_000;

    private static final int PER_CONTACT_MAX = 3;
    private static final Duration PER_CONTACT_WINDOW = Duration.ofHours(1);
    private static final int PER_ADDRESS_MAX = 10;
    private static final Duration PER_ADDRESS_WINDOW = Duration.ofHours(1);
    private static final int GLOBAL_MAX = 200;
    private static final Duration GLOBAL_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger globalCount = new AtomicInteger();
    private volatile Instant globalWindowStart;

    public SupportRateLimiter() {
        this(Clock.systemUTC());
    }

    public SupportRateLimiter(Clock clock) {
        this.clock = clock;
        this.globalWindowStart = clock.instant();
    }

    /**
     * Records one submission attempt. Returns the reason it is refused, or {@code null} when it
     * is allowed. Called once per accepted-looking request, before anything is persisted.
     */
    public Decision check(String contactKey, String addressKey) {
        Instant now = clock.instant();
        prune(now);
        if (!globalAllows(now)) {
            return Decision.GLOBAL;
        }
        if (!allows("c:" + contactKey, PER_CONTACT_MAX, PER_CONTACT_WINDOW, now)) {
            return Decision.CONTACT;
        }
        if (!allows("a:" + addressKey, PER_ADDRESS_MAX, PER_ADDRESS_WINDOW, now)) {
            return Decision.ADDRESS;
        }
        return null;
    }

    private boolean globalAllows(Instant now) {
        synchronized (this) {
            if (globalWindowStart.plus(GLOBAL_WINDOW).isBefore(now)) {
                globalWindowStart = now;
                globalCount.set(0);
            }
            if (globalCount.get() >= GLOBAL_MAX) {
                return false;
            }
            globalCount.incrementAndGet();
            return true;
        }
    }

    private boolean allows(String key, int max, Duration window, Instant now) {
        Window existing = windows.get(key);
        if (existing == null && windows.size() >= MAX_KEYS) {
            return false;
        }
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || current.startedAt.plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        return updated.count <= max;
    }

    /** Drops every window whose longest possible lifetime has passed. */
    private void prune(Instant now) {
        Instant cutoff = now.minus(PER_CONTACT_WINDOW.compareTo(PER_ADDRESS_WINDOW) >= 0
                ? PER_CONTACT_WINDOW : PER_ADDRESS_WINDOW);
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().startedAt.isBefore(cutoff)) {
                it.remove();
            }
        }
    }

    int trackedKeys() {
        return windows.size();
    }

    public enum Decision {
        CONTACT,
        ADDRESS,
        GLOBAL
    }

    private record Window(Instant startedAt, int count) {
    }
}
