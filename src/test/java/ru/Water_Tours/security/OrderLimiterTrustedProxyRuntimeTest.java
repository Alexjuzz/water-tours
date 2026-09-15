package ru.Water_Tours.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.Water_Tours.ticket.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code ORDER_RATE_LIMIT_ENABLED=true} would actually do on the deployed machine.
 *
 * <p>The production report of 2026-09-15 could prove that nginx sends a single self-observed
 * {@code X-Forwarded-For} and that the loopback peer is inside the trusted list, but not <i>the
 * value the application derives per request</i> - nothing on that system exposes it, the
 * application deliberately never logs a client address, and the only live way to observe it would
 * have been to flood a real endpoint. So the limiter was left off with the gate written down.
 *
 * <p>This closes that gap in the one place where it can be closed honestly: the same application,
 * the same {@code RemoteIpValve}, the same filter, started on a real port, with the limiter on and
 * a window small enough to exhaust in three requests. {@link
 * ru.Water_Tours.ticket.ratelimit.OrderCreationRateLimitFilter} keys on
 * {@code request.getRemoteAddr()}, so if the valve resolves that to the forwarded client address,
 * two different customers must get two independent buckets - and that is an observable fact, not a
 * configuration claim.
 *
 * <p><b>No order is ever created.</b> Every probe posts {@code {}}, which fails {@code @Valid}
 * before the controller does anything, and the order count is asserted unchanged at the end. No
 * customer address is recorded anywhere: the addresses here are the reserved documentation ranges
 * from RFC 5737.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLimiterTrustedProxyRuntimeTest extends ForwardedHeaderRuntimeTestSupport {

    private static final String CUSTOMER = "203.0.113.11";
    private static final String SOMEBODY_ELSE = "198.51.100.12";

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        for (String property : BASE_PROPERTIES) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The deployed shape: nginx on the same host, so the peer is loopback and trusted.
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.\\d+\\.\\d+\\.\\d+|0:0:0:0:0:0:0:1|::1");
        // The production default is 20 per 10 minutes. Two is the same mechanism, reachable in a
        // test; the window stays long so nothing here depends on wall-clock timing.
        registry.add("order.rate-limit.enabled", () -> "true");
        registry.add("order.rate-limit.max-per-window", () -> "2");
        registry.add("order.rate-limit.window", () -> "10m");
    }

    @LocalServerPort int port;
    @Autowired OrderRepository orders;

    /**
     * The whole question, in one test: does the limiter count per customer, or per proxy?
     *
     * <p>If it keyed on the TCP peer, all four requests below would share one bucket and the third
     * would be refused whoever sent it - which is the self-inflicted outage the limiter was left
     * off to avoid. The fourth request, from a different forwarded address, is what tells the two
     * cases apart.
     */
    @Test
    void theLimiterCountsPerForwardedClientAddressAndNotPerProxy() throws Exception {
        long before = orders.count();

        // Normal use: a customer who orders twice is not interfered with.
        assertThat(orderAttemptFrom(port, CUSTOMER)).isEqualTo(400);
        assertThat(orderAttemptFrom(port, CUSTOMER)).isEqualTo(400);

        // Third from the same address: the bucket is spent.
        assertThat(orderAttemptFrom(port, CUSTOMER)).isEqualTo(429);

        // A different customer, arriving through the same proxy over the same loopback peer, is
        // untouched. This is the assertion the live system could not make.
        assertThat(orderAttemptFrom(port, SOMEBODY_ELSE)).isEqualTo(400);

        assertThat(orders.count()).isEqualTo(before);
    }

    /** A refused request has to tell the caller when to come back, or the storefront cannot retry. */
    @Test
    void aRefusalCarriesRetryAfter() throws Exception {
        String spent = "203.0.113.13";
        assertThat(orderAttemptFrom(port, spent)).isEqualTo(400);
        assertThat(orderAttemptFrom(port, spent)).isEqualTo(400);

        var refusal = orderAttemptResponseFrom(port, spent);
        assertThat(refusal.statusCode()).isEqualTo(429);
        assertThat(refusal.headers().firstValue("Retry-After")).isPresent();
        assertThat(Long.parseLong(refusal.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive()
                .isLessThanOrEqualTo(600);
        assertThat(refusal.body()).contains("Слишком много заказов");
    }

    /** The limiter is scoped to order creation; reading an order's status must never be throttled. */
    @Test
    void theLimiterDoesNotTouchAnythingButOrderCreation() throws Exception {
        String spent = "203.0.113.14";
        assertThat(orderAttemptFrom(port, spent)).isEqualTo(400);
        assertThat(orderAttemptFrom(port, spent)).isEqualTo(400);
        assertThat(orderAttemptFrom(port, spent)).isEqualTo(429);

        // Same address, same moment, a GET on the order surface: answered by the application, not
        // by the limiter. 404/400 are both fine - 429 is not.
        assertThat(orderStatusAttemptFrom(port, spent)).isNotEqualTo(429);
    }
}
