package ru.Water_Tours.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same application, with the caller OUTSIDE the trusted-proxy list - a direct connection to
 * the backend port, inventing its own forwarded headers.
 *
 * <p>This is the case {@code forward-headers-strategy: framework} would have got wrong, and it is
 * why {@code native} was chosen: {@code framework} honours {@code X-Forwarded-For} from anybody
 * and reads the leftmost entry, the one a client picks. Here nothing forwarded is believed, so a
 * spoofed header can neither claim a transport the request does not have nor buy a fresh throttle
 * bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UntrustedProxyRuntimeTest extends ForwardedHeaderRuntimeTestSupport {

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        for (String property : BASE_PROPERTIES) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // A single address that the loopback test client cannot possibly be. Everything the
        // client forwards is therefore untrusted.
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.99\\.99\\.99");
    }

    @LocalServerPort int port;
    @Autowired LoginAttemptService loginAttemptService;

    @Test
    void aSpoofedHttpsHeaderBuysNeitherASecureCookieNorHsts() throws Exception {
        HttpResponse<String> response = openLoginPage(port, "203.0.113.7").response();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(setCookies(response)).contains("JSESSIONID").doesNotContain("Secure");
        // Writing HSTS over plain HTTP would be both wrong and unwithdrawable for a year.
        assertThat(header(response, "Strict-Transport-Security")).isEmpty();
    }

    /** The headers that do not depend on the transport are still there. */
    @Test
    void cspAndReferrerPolicyDoNotDependOnBeingBehindAProxy() throws Exception {
        HttpResponse<String> response = openLoginPage(port, "203.0.113.7").response();

        assertThat(header(response, "Content-Security-Policy")).isPresent();
        assertThat(header(response, "Referrer-Policy")).contains("same-origin");
    }

    /**
     * The attack the per-address key would otherwise invite: rotate {@code X-Forwarded-For} and
     * get an unlimited number of fresh buckets. Because the peer is untrusted, every one of these
     * attempts counts against the real peer instead, and the throttle closes on schedule.
     */
    @Test
    void rotatingASpoofedForwardedForDoesNotBuyFreshThrottleBuckets() throws Exception {
        loginAttemptService.reset("127.0.0.1");

        assertThat(failedLoginFrom(port, "203.0.113.1")).isEqualTo(302);
        assertThat(failedLoginFrom(port, "203.0.113.2")).isEqualTo(302);
        assertThat(failedLoginFrom(port, "203.0.113.3")).isEqualTo(302);

        // A fourth invented address, and the fourth attempt is refused all the same.
        assertThat(failedLoginFrom(port, "203.0.113.4")).isEqualTo(429);
    }
}
