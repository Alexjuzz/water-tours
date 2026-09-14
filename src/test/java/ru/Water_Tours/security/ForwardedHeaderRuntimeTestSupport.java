package ru.Water_Tours.security;

import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for the two runtime transport tests.
 *
 * <p>These are deliberately NOT MockMvc. MockMvc never runs Tomcat's {@code RemoteIpValve}, which
 * is the whole subject here: the valve is what decides whether {@code X-Forwarded-For} and
 * {@code X-Forwarded-Proto} are believed, and everything downstream - {@code Secure} on the
 * session cookie, whether HSTS is written at all, and which key the login throttle counts against
 * - follows from that one decision. A MockMvc assertion about those headers only proves what
 * Spring Security would do if Tomcat had already decided the request was secure, which is the
 * question, not the answer.
 *
 * <p>So the application is started on a real port and driven with a real HTTP client. The two
 * subclasses differ in exactly one property: whether the loopback address the test client comes
 * from is inside {@code server.tomcat.remoteip.internal-proxies}.
 *
 * <p>The PostgreSQL container is a singleton shared by both classes rather than a {@code @Container}
 * field, so switching trust settings costs a context restart and not a second database.
 */
abstract class ForwardedHeaderRuntimeTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** Properties every runtime test here needs; the trust setting is added by the subclass. */
    static final String[] BASE_PROPERTIES = {
            "spring.profiles.active=test",
            "yookassa.shopId=test", "yookassa.secretKey=test", "app.base-url=http://localhost:8080",
            "spring.mail.username=test@example.invalid", "spring.mail.password=test",
            "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
            "management.health.mail.enabled=false", "payments.reconciliation.enabled=false",
            "tickets.issuance.enabled=false", "order.expiration-check-interval=86400000",
            "staff.username=test-staff", "staff.password=test-only-password",
            "staff.remember-me-key=test-only-remember-me-key",
            "support.owner-telegram-chat-id=",
            "server.forward-headers-strategy=native",
            // Small enough to exhaust quickly; the global backstop is pushed out of the way so
            // that what these tests observe is the per-address bucket and nothing else.
            "staff.login-throttle.max-attempts=3",
            "staff.login-throttle.global-max-attempts=1000",
    };

    private static final Pattern SESSION = Pattern.compile("JSESSIONID=[^;]+");

    private static final Pattern CSRF =
            Pattern.compile("name=\"_csrf\"[^>]*?value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*?name=\"_csrf\"");

    /**
     * A fresh client per call, and the session cookie is carried by hand rather than by a
     * CookieManager. That is not fussiness: when the valve believes the forwarded
     * {@code X-Forwarded-Proto: https}, Tomcat marks {@code JSESSIONID} {@code Secure}, and a
     * cookie store would then refuse to send it back over the test's plain-HTTP hop - so the POST
     * would arrive with no session, fail CSRF, and the throttle would never see a login attempt
     * at all. A real browser over real TLS sends it, which is the situation being modelled.
     */
    private static HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The {@code JSESSIONID=value} pair from a Set-Cookie, ready to be sent straight back. */
    static Optional<String> sessionCookie(HttpResponse<?> response) {
        Matcher matcher = SESSION.matcher(setCookies(response));
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    /**
     * Fetches the login page as if proxied from {@code clientAddress} over TLS, and reports what
     * the server said about the transport.
     */
    LoginPage openLoginPage(int port, String clientAddress) throws IOException, InterruptedException {
        HttpClient http = client();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                        .header("X-Forwarded-For", clientAddress)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "water-tours.ru")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new LoginPage(http, response, csrfToken(response.body()));
    }

    /**
     * One failed login from {@code clientAddress}. Returns the status the server answered with:
     * 302 for "credentials rejected" (Spring redirects back to /login?error) and 429 once the
     * throttle has taken over.
     */
    int failedLoginFrom(int port, String clientAddress) throws IOException, InterruptedException {
        LoginPage page = openLoginPage(port, clientAddress);
        String form = "username=test-staff&password=definitely-wrong&_csrf=" + page.csrf();
        HttpRequest.Builder post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                .header("X-Forwarded-For", clientAddress)
                .header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        sessionCookie(page.response()).ifPresent(cookie -> post.header("Cookie", cookie));
        return page.http().send(post.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static String csrfToken(String html) {
        Matcher matcher = CSRF.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("No _csrf token on the login page - the page changed shape");
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    static Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    /** Every Set-Cookie on the response, joined - the session cookie is the one that matters. */
    static String setCookies(HttpResponse<?> response) {
        Map<String, List<String>> headers = response.headers().map();
        List<String> values = headers.getOrDefault("set-cookie", headers.getOrDefault("Set-Cookie", List.of()));
        return String.join(" | ", values);
    }

    record LoginPage(HttpClient http, HttpResponse<String> response, String csrf) {
    }
}
