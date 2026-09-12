package ru.Water_Tours.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key (IP) sliding-window login failure counter. In-memory: the app runs as a single
 * instance (see ops/disaster-recovery), so this does not need to be shared via Redis.
 */
@Component
public class LoginAttemptService {

    private final int maxAttempts;
    private final Duration window;
    private final Duration blockDuration;
    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(10, Duration.ofMinutes(15), Duration.ofMinutes(15), Clock.systemUTC());
    }

    public LoginAttemptService(int maxAttempts, Duration window, Duration blockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.blockDuration = blockDuration;
        this.clock = clock;
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
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
        Attempts attempts = attemptsByKey.get(key);
        if (attempts == null || attempts.blockedUntil == null) {
            return false;
        }
        if (attempts.blockedUntil.isBefore(clock.instant())) {
            attemptsByKey.remove(key);
            return false;
        }
        return true;
    }

    public Duration retryAfter(String key) {
        Attempts attempts = attemptsByKey.get(key);
        if (attempts == null || attempts.blockedUntil == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), attempts.blockedUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private record Attempts(Instant windowStartedAt, int count, Instant blockedUntil) {
    }
}
