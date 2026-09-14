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
 * M-1/M-3 with the valve actually running, and the TCP peer inside the trusted list.
 *
 * <p>This is the deployed shape: nginx on the same host talks to the backend over loopback, so the
 * peer is trusted and its forwarded headers are believed. What has to follow from that, and is
 * asserted here rather than inferred from configuration:
 * <ul>
 *   <li>the request is treated as HTTPS, so the session cookie is {@code Secure} and HSTS is
 *       written at all;</li>
 *   <li>the login throttle counts against the forwarded client address, so one address exhausting
 *       it leaves every other address alone.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustedProxyRuntimeTest extends ForwardedHeaderRuntimeTestSupport {

    private static final String CUSTOMER = "203.0.113.7";
    private static final String SOMEBODY_ELSE = "198.51.100.9";

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        for (String property : BASE_PROPERTIES) {
            int split = property.indexOf('=');
            registry.add(property.substring(0, split), () -> property.substring(split + 1));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Loopback only: exactly what the deployed backend sees from nginx on the same host, and
        // what the test client is.
        registry.add("server.tomcat.remoteip.internal-proxies", () -> "127\\.\\d+\\.\\d+\\.\\d+|0:0:0:0:0:0:0:1|::1");
    }

    @LocalServerPort int port;
    @Autowired LoginAttemptService loginAttemptService;

    @Test
    void aTrustedProxySayingHttpsGivesASecureSessionCookieAndHsts() throws Exception {
        HttpResponse<String> response = openLoginPage(port, CUSTOMER).response();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(setCookies(response)).contains("JSESSIONID").contains("Secure").contains("HttpOnly");
        assertThat(header(response, "Strict-Transport-Security")).isPresent();
        assertThat(header(response, "Strict-Transport-Security").orElseThrow())
                .contains("max-age=31536000")
                // Off until every *.water-tours.ru host is known to serve HTTPS.
                .doesNotContain("includeSubDomains");
    }

    /** These three do not depend on the transport, and must be on the staff surface regardless. */
    @Test
    void theStaffSurfaceCarriesCspReferrerPolicyAndSameSite() throws Exception {
        HttpResponse<String> response = openLoginPage(port, CUSTOMER).response();

        assertThat(header(response, "Content-Security-Policy").orElseThrow())
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'")
                .contains("form-action 'self'")
                .contains("base-uri 'none'");
        assertThat(header(response, "Referrer-Policy")).contains("same-origin");
        assertThat(setCookies(response)).contains("SameSite=Lax");
    }

    /**
     * M-3. Before the valve, every visitor shared one counter keyed on the proxy, so ten bad
     * logins from anybody locked the owner out too.
     */
    @Test
    void oneClientAddressExhaustingTheThrottleLeavesAnotherAddressWorking() throws Exception {
        loginAttemptService.reset(CUSTOMER);
        loginAttemptService.reset(SOMEBODY_ELSE);
        loginAttemptService.reset("127.0.0.1");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(failedLoginFrom(port, CUSTOMER)).isEqualTo(302);
        }
        assertThat(failedLoginFrom(port, CUSTOMER)).isEqualTo(429);

        // The proof that the buckets are independent, and that the key is the forwarded address
        // and not the loopback peer both requests actually arrive from.
        assertThat(failedLoginFrom(port, SOMEBODY_ELSE)).isEqualTo(302);
    }
}
