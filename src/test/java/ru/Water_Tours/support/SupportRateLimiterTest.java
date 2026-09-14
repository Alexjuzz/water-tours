package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SupportRateLimiterTest {

    /** Advanceable clock so the windows can be tested without sleeping. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-14T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void oneContactIsCappedAndRecoversAfterTheWindow() {
        TestClock clock = new TestClock();
        SupportRateLimiter limiter = new SupportRateLimiter(clock);

        assertThat(limiter.check("guest@example.ru", "1.1.1.1")).isNull();
        assertThat(limiter.check("guest@example.ru", "1.1.1.1")).isNull();
        assertThat(limiter.check("guest@example.ru", "1.1.1.1")).isNull();
        assertThat(limiter.check("guest@example.ru", "1.1.1.1")).isEqualTo(SupportRateLimiter.Decision.CONTACT);

        clock.advance(Duration.ofHours(1).plusMinutes(1));
        assertThat(limiter.check("guest@example.ru", "1.1.1.1")).isNull();
    }

    @Test
    void theGlobalCapStopsAFloodThatRotatesContacts() {
        TestClock clock = new TestClock();
        SupportRateLimiter limiter = new SupportRateLimiter(clock);

        SupportRateLimiter.Decision refusal = null;
        for (int i = 0; i < 200 && refusal == null; i++) {
            refusal = limiter.check("guest" + i + "@example.ru", "10.0.0." + (i % 250));
        }
        // The per-contact and per-address buckets never fire here - every attempt uses a fresh
        // key - so this proves the backstop is what actually bounds a distributed flood.
        assertThat(refusal).isEqualTo(SupportRateLimiter.Decision.GLOBAL);
    }

    @Test
    void keyStorageStaysBoundedUnderAKeyRotatingFlood() {
        TestClock clock = new TestClock();
        SupportRateLimiter limiter = new SupportRateLimiter(clock);

        for (int i = 0; i < SupportRateLimiter.MAX_KEYS * 2; i++) {
            limiter.check("guest" + i + "@example.ru", "10.0.0." + (i % 250));
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(SupportRateLimiter.MAX_KEYS);
    }

    @Test
    void expiredWindowsArePrunedRatherThanAccumulated() {
        TestClock clock = new TestClock();
        SupportRateLimiter limiter = new SupportRateLimiter(clock);

        for (int i = 0; i < 20; i++) {
            limiter.check("guest" + i + "@example.ru", "10.0.0.1");
        }
        int before = limiter.trackedKeys();
        clock.advance(Duration.ofHours(3));
        limiter.check("later@example.ru", "10.0.0.2");

        assertThat(before).isGreaterThan(2);
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(2);
    }
}
