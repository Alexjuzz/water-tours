package ru.Water_Tours.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    @Test
    void allowsAttemptsBelowThreshold() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15), Duration.ofMinutes(15), mutableClock(now));

        for (int i = 0; i < 4; i++) {
            service.recordFailure("1.2.3.4");
        }

        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void blocksAfterThresholdWithinWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15), Duration.ofMinutes(15), mutableClock(now));

        for (int i = 0; i < 5; i++) {
            service.recordFailure("1.2.3.4");
        }

        assertThat(service.isBlocked("1.2.3.4")).isTrue();
        assertThat(service.retryAfter("1.2.3.4")).isPositive();
    }

    @Test
    void unblocksAfterBlockDurationElapses() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15), Duration.ofMinutes(15), mutableClock(now));

        for (int i = 0; i < 5; i++) {
            service.recordFailure("1.2.3.4");
        }
        now.set(now.get().plus(Duration.ofMinutes(16)));

        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void resetClearsFailureCount() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15), Duration.ofMinutes(15), mutableClock(now));

        for (int i = 0; i < 4; i++) {
            service.recordFailure("1.2.3.4");
        }
        service.reset("1.2.3.4");
        for (int i = 0; i < 4; i++) {
            service.recordFailure("1.2.3.4");
        }

        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15), Duration.ofMinutes(15), mutableClock(now));

        for (int i = 0; i < 5; i++) {
            service.recordFailure("1.2.3.4");
        }

        assertThat(service.isBlocked("5.6.7.8")).isFalse();
    }
}
