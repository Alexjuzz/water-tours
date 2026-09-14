package ru.Water_Tours.ticket.ticketController;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start if the {@code local-checkout} profile is active on anything that looks like a
 * real deployment.
 *
 * <p>That profile issues fully valid, paid tickets with no provider call
 * ({@code POST /api/v1/orders/{id}/test-pay}). Its only guard is that the caller's address is
 * loopback or explicitly allowlisted, which is a deployment convention rather than an
 * authorisation - and the allowlist that local development needs (the container bridge gateway) is
 * exactly the address a reverse proxy on the host presents. "Never enable it on production" is
 * written down in docs/AI-CURRENT-STATE.md; this makes the machine enforce it, so a stray
 * SPRING_PROFILES_ACTIVE cannot quietly turn free tickets on.
 */
@Component
@Profile("local-checkout")
public class LocalCheckoutProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(LocalCheckoutProfileGuard.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "host.docker.internal");

    private final String baseUrl;

    public LocalCheckoutProfileGuard(@Value("${app.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    void verifyLocalDeployment() {
        String host = hostOf(baseUrl);
        if (host == null || !LOCAL_HOSTS.contains(host)) {
            throw new IllegalStateException(
                    "The local-checkout profile issues free paid tickets and must never run outside local "
                            + "development. app.base-url is '" + baseUrl + "', which is not a localhost URL. "
                            + "Remove local-checkout from SPRING_PROFILES_ACTIVE, or point app.base-url at localhost.");
        }
        log.warn("local-checkout profile active: test payments are enabled and issue real tickets. "
                + "This must never be a production host.");
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
