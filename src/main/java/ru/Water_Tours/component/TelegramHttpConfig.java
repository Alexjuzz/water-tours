package ru.Water_Tours.component;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client the bot talks to Telegram with, and why it is no longer the default one.
 *
 * <h2>The failure this exists to remove</h2>
 *
 * On the production host, polling failed with {@code ResourceAccessException} on roughly two
 * attempts in three while the same bot succeeded on the third - measured on 2026-09-15 and
 * recorded in the production readiness report. What that report measured, from inside the
 * container: {@code 149.154.167.220:443} open, {@code 149.154.166.110:443} and
 * {@code 149.154.175.50:443} blocked, ordinary IPv4 egress (YooKassa) working. All three of those
 * addresses are {@code api.telegram.org}, handed out in rotation by DNS.
 *
 * <p>That becomes an explanation once one detail of the JDK is added.
 * {@link SimpleClientHttpRequestFactory} is {@code HttpURLConnection}, and
 * {@code sun.net.NetworkClient} connects to {@code new InetSocketAddress(host, port)} - a
 * <b>single</b> address, whichever one resolution returned first. When that is one of the blocked
 * two the attempt fails and nothing tries the third. The success rate is then simply the share of
 * lookups that happen to put the reachable address first, which is what "about one in three" was.
 *
 * <h2>The fix, and what it deliberately is not</h2>
 *
 * Apache HttpClient's connection operator iterates over <i>every</i> address the resolver returns
 * and fails only when all of them have failed. The hostname is kept for SNI and for certificate
 * validation, so this is not address pinning: there is no IP literal anywhere in this file, DNS
 * stays in charge, and the day Telegram rotates its addresses this keeps working where an
 * {@code /etc/hosts} entry would silently stop. No proxy and no new external service.
 *
 * <p>It is also not a claim that the network is healthy. Two of three addresses being blocked
 * remains an infrastructure fact for the owner to decide about; this stops that fact from costing
 * two thirds of the bot's requests.
 *
 * <h2>Reversibility</h2>
 *
 * {@code telegram.connection-failover=false} restores the previous JDK client exactly, with the
 * same timeouts, without a rebuild - wired through {@code TELEGRAM_CONNECTION_FAILOVER} in
 * {@code compose.yml}.
 */
@Configuration
public class TelegramHttpConfig {

    /** Long enough for a TLS handshake over a slow path, short enough to move on to the next address. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** getUpdates holds the connection open for 25 s by request, so this has to outlast that. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(35);

    /**
     * @param apiBase where Telegram lives. Defaults to the real API and is expected to stay there;
     *                it is a property so that an acceptance run can point the whole client at a
     *                local mock and prove the flow without a single message reaching a real chat.
     *                Setting it to anything other than the default in production would send the
     *                bot token to that host, so it belongs in a local profile and nowhere else.
     */
    @Bean("telegramRestClient")
    public RestClient telegramRestClient(@Value("${telegram.bot-token:}") String botToken,
                                         @Value("${telegram.api-base:https://api.telegram.org}") String apiBase,
                                         @Value("${telegram.connection-failover:true}") boolean failover) {
        return RestClient.builder()
                .requestFactory(failover
                        ? addressFailoverRequestFactory(SystemDefaultDnsResolver.INSTANCE,
                                CONNECT_TIMEOUT, RESPONSE_TIMEOUT)
                        : singleAddressRequestFactory(CONNECT_TIMEOUT, RESPONSE_TIMEOUT))
                .baseUrl(trimTrailingSlash(apiBase) + "/bot" + botToken)
                .build();
    }

    /**
     * A request factory that tries every address a host resolves to before giving up.
     *
     * <p>{@code dnsResolver} is a parameter rather than a hardcoded default so the behaviour can
     * be proven against a resolver whose answers the test chooses - see
     * {@code TelegramConnectionFailoverTest}. Production always passes the system resolver.
     *
     * <p>The pool is deliberately small: this client serves one long poll and the occasional
     * outbound message. {@code validateAfterInactivity} makes a pooled connection be rechecked
     * rather than handed out and failed on, which matters on a path that drops connections.
     */
    public static ClientHttpRequestFactory addressFailoverRequestFactory(
            DnsResolver dnsResolver, Duration connectTimeout, Duration responseTimeout) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setMaxConnTotal(8)
                .setMaxConnPerRoute(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(10))
                        .build())
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                        .build())
                // A send that may already have reached Telegram is not replayed here. A refused
                // notification is retried by SupportNotificationJob with backoff, where the
                // attempt count and the last error are stored and visible to staff.
                .disableAutomaticRetries()
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    /** Exactly what this bean built before the failover client: one address, then give up. */
    public static ClientHttpRequestFactory singleAddressRequestFactory(
            Duration connectTimeout, Duration responseTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(responseTimeout);
        return factory;
    }

    public static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
