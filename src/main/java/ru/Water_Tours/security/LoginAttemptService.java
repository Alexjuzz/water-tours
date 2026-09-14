package ru.Water_Tours.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login failure counter with two buckets.
 *
 * <p><b>Per key (the client address).</b> The effective limit. It only became meaningful once the
 * backend started seeing the real client address instead of the reverse proxy
 * ({@code server.forward-headers-strategy=native}); before that every visitor shared one key, so
 * ten bad logins from anybody locked the console out for everyone.
 *
 * <p><b>Global.</b> A last-resort brake against an attack spread over many addresses, set far
 * looser than the per-key limit precisely so it is not the cheap denial-of-service the single
 * global bucket used to be. It is a deliberate trade-off: a wide enough distributed attack can
 * still block the console for the configured (short) block duration.
 *
 * <p>In-memory: the app runs as a single instance (see ops/disaster-recovery). The map is bounded
 * - expired windows are pruned on write and, past {@code maxTrackedKeys} live keys, a new key is
 * not tracked at all rather than growing without limit. Not tracking fails open for that one
 * address, which is the right direction here: the global bucket still applies, and the
 * alternative (refusing logins) would hand an attacker the lockout back.
 */
@Component
public class LoginAttemptService {

    private final int maxAttempts;
    private final Duration window;
    private final Duration blockDuration;
    private final int globalMaxAttempts;
    private final Duration globalBlockDuration;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();

    private volatile Instant globalWindowStartedAt;
    private volatile int globalCount;
    private volatile Instant globalBlockedUntil;

    public LoginAttemptService() {
        this(10, Duration.ofMinutes(15), Duration.ofMinutes(15), Clock.systemUTC());
    }

    public LoginAttemptService(int maxAttempts, Duration window, Duration blockDuration, Clock clock) {
        this(maxAttempts, window, blockDuration, 100, Duration.ofMinutes(2), 10_000, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LoginAttemptService(
            @Value("${staff.login-throttle.max-attempts:10}") int maxAttempts,
            @Value("${staff.login-throttle.window:15m}") Duration window,
            @Value("${staff.login-throttle.block-duration:15m}") Duration blockDuration,
            @Value("${staff.login-throttle.global-max-attempts:100}") int globalMaxAttempts,
            @Value("${staff.login-throttle.global-block-duration:2m}") Duration globalBlockDuration,
            @Value("${staff.login-throttle.max-tracked-keys:10000}") int maxTrackedKeys) {
        this(maxAttempts, window, blockDuration, globalMaxAttempts, globalBlockDuration, maxTrackedKeys,
                Clock.systemUTC());
    }

    public LoginAttemptService(int maxAttempts, Duration window, Duration blockDuration,
                               int globalMaxAttempts, Duration globalBlockDuration, int maxTrackedKeys,
                               Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.blockDuration = blockDuration;
        this.globalMaxAttempts = globalMaxAttempts;
        this.globalBlockDuration = globalBlockDuration;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
        this.globalWindowStartedAt = clock.instant();
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        recordGlobalFailure(now);
        prune(now);
        if (!attemptsByKey.containsKey(key) && attemptsByKey.size() >= maxTrackedKeys) {
            return;
        }
        attemptsByKey.compute(key, (k, existing) -> {
            if (existing == null || existing.windowStartedAt.plus(window).isBefore(now)) {
                return new Attempts(now, 1, null);
            }
            return new Attempts(existing.windowStartedAt, existing.count + 1,
                    existing.count + 1 >= maxAttempts ? now.plus(blockDuration) : existing.blockedUntil);
        });
    }

    public void reset(String key) {
        attemptsByKey.remove(key);
    }

    public boolean isBlocked(String key) {
        return blockedUntil(key) != null;
    }

    public Duration retryAfter(String key) {
        Instant until = blockedUntil(key);
        if (until == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), until);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** Visible for tests: how many address keys are currently held. */
    int trackedKeys() {
        return attemptsByKey.size();
    }

    // REGION PRIVATE METHODS

    private Instant blockedUntil(String key) {
        Instant now = clock.instant();
        Instant until = null;

        Attempts attempts = attemptsByKey.get(key);
        if (attempts != null && attempts.blockedUntil != null) {
            if (attempts.blockedUntil.isBefore(now)) {
                attemptsByKey.remove(key, attempts);
            } else {
                until = attempts.blockedUntil;
            }
        }

        Instant global = globalBlockedUntil;
        if (global != null) {
            if (global.isBefore(now)) {
                globalBlockedUntil = null;
            } else if (until == null || global.isAfter(until)) {
                until = global;
            }
        }
        return until;
    }

    private synchronized void recordGlobalFailure(Instant now) {
        if (globalWindowStartedAt.plus(window).isBefore(now)) {
            globalWindowStartedAt = now;
            globalCount = 0;
        }
        globalCount++;
        if (globalCount >= globalMaxAttempts) {
            globalBlockedUntil = now.plus(globalBlockDuration);
        }
    }

    /** Drops windows that can no longer block anything, so the map tracks only live keys. */
    private void prune(Instant now) {
        Instant cutoff = now.minus(window).minus(blockDuration);
        for (Iterator<Map.Entry<String, Attempts>> it = attemptsByKey.entrySet().iterator(); it.hasNext(); ) {
            Attempts value = it.next().getValue();
            boolean blockStillRunning = value.blockedUntil != null && !value.blockedUntil.isBefore(now);
            if (!blockStillRunning && value.windowStartedAt.isBefore(cutoff)) {
                it.remove();
            }
        }
    }

    // ENDREGION

    private record Attempts(Instant windowStartedAt, int count, Instant blockedUntil) {
    }
}
