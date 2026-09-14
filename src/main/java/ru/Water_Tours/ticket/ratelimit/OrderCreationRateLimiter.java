package ru.Water_Tours.ticket.ratelimit;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-address cap on anonymous order creation.
 *
 * <p>Creating an order is unauthenticated by design - a customer has no account - so nothing
 * stopped a script from writing order and order_item rows indefinitely. Money was never at risk
 * (the amount is computed server-side and the provider would refuse an absurd total), but the rows
 * are never deleted, they reach the staff pages and the mail queue, and each one consumes a cache
 * entry.
 *
 * <p><b>Disabled by default, and that is deliberate.</b> This limit is only meaningful if the
 * backend sees the real client address; if the address collapses to the reverse proxy's, every
 * customer shares one bucket and the storefront throttles itself. That is a worse outcome than the
 * abuse it prevents, so enabling it is a separate decision taken after a live check
 * (see {@code order.rate-limit.enabled}).
 *
 * <p>Storage is bounded the same way the support limiter is: expired windows are pruned on write,
 * and past {@code maxTrackedKeys} live keys a new key is not tracked at all. Not tracking fails
 * open for that one address - the alternative, refusing, would let a key-rotating flood deny
 * ordinary customers the ability to buy.
 */
public class OrderCreationRateLimiter {

    private final boolean enabled;
    private final int maxPerWindow;
    private final Duration window;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public OrderCreationRateLimiter(boolean enabled, int maxPerWindow, Duration window, int maxTrackedKeys) {
        this(enabled, maxPerWindow, window, maxTrackedKeys, Clock.systemUTC());
    }

    public OrderCreationRateLimiter(boolean enabled, int maxPerWindow, Duration window, int maxTrackedKeys,
                                    Clock clock) {
        this.enabled = enabled;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return true when this request may proceed. */
    public boolean allow(String addressKey) {
        if (!enabled) {
            return true;
        }
        Instant now = clock.instant();
        prune(now);
        if (!windows.containsKey(addressKey) && windows.size() >= maxTrackedKeys) {
            return true;
        }
        Window updated = windows.compute(addressKey, (key, current) -> {
            if (current == null || current.startedAt.plus(window).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        return updated.count <= maxPerWindow;
    }

    public long retryAfterSeconds(String addressKey) {
        Window current = windows.get(addressKey);
        if (current == null) {
            return window.toSeconds();
        }
        long remaining = Duration.between(clock.instant(), current.startedAt.plus(window)).toSeconds();
        return Math.max(1, remaining);
    }

    int trackedKeys() {
        return windows.size();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(window);
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().startedAt.isBefore(cutoff)) {
                it.remove();
            }
        }
    }

    private record Window(Instant startedAt, int count) {
    }
}
