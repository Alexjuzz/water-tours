package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffTestEmailGuardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-13T10:00:00Z"));

    private StaffTestEmailGuard guard() {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new StaffTestEmailGuard(Duration.ofSeconds(60), clock);
    }

    @Test
    void acceptsAndTrimsAUsableAddress() {
        assertThat(guard().requireSendableAddress("  owner@example.ru ")).isEqualTo("owner@example.ru");
    }

    @Test
    void rejectsAnEmptyAddress() {
        assertThatThrownBy(() -> guard().requireSendableAddress("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Укажите email");
    }

    @Test
    void rejectsTheUnroutableServiceDomain() {
        assertThatThrownBy(() -> guard().requireSendableAddress("x" + StaffTestOrderService.TEST_EMAIL_DOMAIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedAddresses() {
        StaffTestEmailGuard guard = guard();
        for (String bad : new String[]{"owner", "owner@", "@example.ru", "owner@example", "a b@example.ru"}) {
            assertThatThrownBy(() -> guard.requireSendableAddress(bad))
                    .as("address %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void secondClickWithinTheCooldownIsRefused() {
        StaffTestEmailGuard guard = guard();
        guard.reserve("staff", "owner@example.ru");

        assertThatThrownBy(() -> guard.reserve("staff", "owner@example.ru"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Повторить можно через");
    }

    @Test
    void theCooldownIgnoresAddressCase() {
        StaffTestEmailGuard guard = guard();
        guard.reserve("staff", "Owner@Example.ru");

        assertThatThrownBy(() -> guard.reserve("staff", "owner@example.ru"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendingIsAllowedAgainOnceTheCooldownPasses() {
        StaffTestEmailGuard guard = guard();
        guard.reserve("staff", "owner@example.ru");
        now.set(now.get().plusSeconds(61));

        guard.reserve("staff", "owner@example.ru");
    }

    @Test
    void aDifferentAddressIsNotBlockedByAnEarlierSend() {
        StaffTestEmailGuard guard = guard();
        guard.reserve("staff", "owner@example.ru");

        guard.reserve("staff", "other@example.ru");
    }
}
