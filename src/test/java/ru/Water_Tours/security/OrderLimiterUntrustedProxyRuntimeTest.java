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
 * The other half of the proof: a caller that is <i>not</i> a trusted proxy, inventing its own
 * forwarded headers.
 *
 * <p>A per-address limiter is only worth enabling if the address cannot be chosen by the person
 * being limited. If {@code X-Forwarded-For} were believed from anybody, a script would rotate it
 * and buy an unlimited number of fresh buckets, and the limiter would stop exactly nothing while
 * still being able to lock out a real customer. So the spoofing case is not a nice-to-have
 * assertion - it is the condition on which enabling the limiter at all depends.
 *
 * <p>This is the same application with one property changed: the trusted list no longer contains
 * the address the test client connects from. {@code server.forward-headers-strategy=native} then
 * discards everything forwarded, the limiter keys on the real peer, and the rotation buys nothing.
 *
 * <p><b>No order is created</b> and no real address is used or recorded; the values below are the
 * RFC 5737 documentation ranges.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLimiterUntrustedProxyRuntimeTest extends ForwardedHeaderRuntimeTestSupport {

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        for (String property : BASE_PROPERTIES) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // One address the loopback test client cannot be, so nothing it forwards is believed.
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.99\\.99\\.99");
        registry.add("order.rate-limit.enabled", () -> "true");
        registry.add("order.rate-limit.max-per-window", () -> "2");
        registry.add("order.rate-limit.window", () -> "10m");
    }

    @LocalServerPort int port;
    @Autowired OrderRepository orders;

    @Test
    void rotatingASpoofedForwardedForBuysNoFreshOrderBuckets() throws Exception {
        long before = orders.count();

        assertThat(orderAttemptFrom(port, "203.0.113.21")).isEqualTo(400);
        assertThat(orderAttemptFrom(port, "203.0.113.22")).isEqualTo(400);

        // A third, freshly invented address. If the header were trusted this would be a brand new
        // bucket and answer 400; it counts against the real peer instead.
        assertThat(orderAttemptFrom(port, "203.0.113.23")).isEqualTo(429);
        // And so does a fourth, so this is the limiter closing and not one unlucky value.
        assertThat(orderAttemptFrom(port, "198.51.100.24")).isEqualTo(429);
        // And with no forwarded header at all, to show the refusal is not an artefact of
        // sending one: the peer is the key either way.
        assertThat(orderAttemptWithNoForwardedHeader(port)).isEqualTo(429);

        assertThat(orders.count()).isEqualTo(before);
    }
}
