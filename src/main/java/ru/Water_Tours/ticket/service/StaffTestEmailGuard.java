package ru.Water_Tours.ticket.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Address check and repeat protection for the staff-triggered test e-mail.
 *
 * The staff form issues a ticket and mails it in one click, so a double click or a resubmitted
 * form would otherwise put two real messages in someone's inbox. A reservation per staff member
 * and address closes that window; the reservation is kept even when the send fails, so retrying a
 * broken mail server is a deliberate act rather than something a stuck button does on its own.
 */
@Component
public class StaffTestEmailGuard {

    // Deliberately permissive: this only has to catch typos and empty input before the ticket is
    // issued. The mail server is the authority on whether an address exists.
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final Duration cooldown;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastSend = new ConcurrentHashMap<>();

    public StaffTestEmailGuard(@Value("${staff.test-email.cooldown:60s}") Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Returns the trimmed address, or explains in Russian why it cannot be used. */
    public String requireSendableAddress(String email) {
        String address = email == null ? "" : email.trim();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Укажите email, на который отправить тестовое письмо.");
        }
        if (address.toLowerCase(Locale.ROOT).endsWith(StaffTestOrderService.TEST_EMAIL_DOMAIN)) {
            throw new IllegalArgumentException("Адрес " + StaffTestOrderService.TEST_EMAIL_DOMAIN
                    + " служебный, письмо туда не уйдёт. Укажите реальный адрес.");
        }
        if (!ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("Адрес выглядит неверно: " + address);
        }
        return address;
    }

    /** Atomically claims the right to send now, or refuses while a recent send is still cooling down. */
    public void reserve(String staffUsername, String email) {
        String key = staffUsername + "|" + email.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        // The outcome is reported through this reference rather than by comparing the stored value:
        // a double click lands in the same instant, so the kept timestamp and the new one are equal
        // and cannot tell the two calls apart.
        AtomicReference<Instant> retryAt = new AtomicReference<>();

        lastSend.compute(key, (k, previous) -> {
            if (previous != null && now.isBefore(previous.plus(cooldown))) {
                retryAt.set(previous.plus(cooldown));
                return previous;
            }
            return now;
        });

        Instant blockedUntil = retryAt.get();
        if (blockedUntil != null) {
            long seconds = Math.max(1, Duration.between(now, blockedUntil).toSeconds());
            throw new IllegalStateException("Тестовое письмо на этот адрес уже отправлялось. "
                    + "Повторить можно через " + seconds + " с.");
        }
    }
}
