package ru.Water_Tours.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.component.TelegramHttpConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof that the Telegram client survives a host whose first address does not answer.
 *
 * <p>This is the whole of the production diagnosis reduced to something that runs in a second and
 * touches no network: a hostname that resolves to two addresses, only one of which has anything
 * listening. On the real host the roles are played by two blocked Telegram addresses and one open
 * one; here they are played by two loopback addresses, and a stub server bound to exactly one of
 * them. <b>Nothing contacts Telegram and no message is sent anywhere.</b>
 *
 * <p>The resolver is injected rather than mocked at the DNS layer, because what is being tested is
 * not name resolution - it is what the HTTP client does with the list it is handed. The old client
 * used the first entry and stopped; the assertion is that the new one does not.
 */
class TelegramConnectionFailoverTest {

    /** Nothing is ever bound here, so a connection attempt is refused immediately. */
    private static final String DEAD = "127.0.0.2";
    private static final String ALIVE = "127.0.0.1";

    private static final String HOSTNAME = "api.telegram.test.invalid";

    private HttpServer server;
    private final AtomicInteger requestsServed = new AtomicInteger();

    @BeforeEach
    void startStubTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(ALIVE), 0), 0);
        server.createContext("/", exchange -> {
            requestsServed.incrementAndGet();
            byte[] body = "{\"ok\":true,\"result\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStubTelegram() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * The dead address is first, which is the case that used to fail outright. Every request must
     * still be answered, and by the live address.
     */
    @Test
    void aHostWhoseFirstAddressRefusesIsStillReached() {
        RestClient client = failoverClientResolving(DEAD, ALIVE);

        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode response = client.get().uri("/getUpdates").retrieve().body(JsonNode.class);
            assertThat(response).isNotNull();
            assertThat(response.path("ok").asBoolean()).isTrue();
        }
        assertThat(requestsServed.get()).isEqualTo(3);
    }

    /** Order must not matter - a rotating resolver puts the live address first only sometimes. */
    @Test
    void theOrderTheResolverReturnsDoesNotDecideTheOutcome() {
        assertThat(failoverClientResolving(ALIVE, DEAD)
                .get().uri("/getUpdates").retrieve().body(JsonNode.class))
                .isNotNull();
        assertThat(failoverClientResolving(DEAD, DEAD, ALIVE)
                .get().uri("/getUpdates").retrieve().body(JsonNode.class))
                .isNotNull();
        assertThat(requestsServed.get()).isEqualTo(2);
    }

    /**
     * The honest boundary: failover is not a way to reach a host that is genuinely unreachable.
     * With no live address the call still fails, and it fails as the same
     * {@code ResourceAccessException} the retry and logging paths already handle - so a real
     * outage is still reported as an outage rather than swallowed.
     */
    @Test
    void whenNoAddressAnswersTheCallStillFailsAndLooksTheSame() {
        assertThatThrownBy(() -> failoverClientResolving(DEAD, DEAD)
                .get().uri("/getUpdates").retrieve().body(JsonNode.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(requestsServed.get()).isZero();
    }

    /**
     * The regression this whole change is about, pinned so it cannot come back unnoticed: the
     * previous client, given the same two addresses in the same order, cannot reach the server.
     * It is the JDK resolving the name itself and taking the first answer - which is why the test
     * points it at the dead address directly rather than at a hostname it would resolve for real.
     */
    @Test
    void theSingleAddressClientIsTheOneThatCouldNotDoThis() {
        RestClient legacy = RestClient.builder()
                .requestFactory(TelegramHttpConfig.singleAddressRequestFactory(
                        Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .baseUrl("http://" + DEAD + ":" + server.getAddress().getPort())
                .build();

        assertThatThrownBy(() -> legacy.get().uri("/getUpdates").retrieve().body(JsonNode.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(requestsServed.get()).isZero();
    }

    /** A client whose resolver answers with exactly the addresses given, in that order. */
    private RestClient failoverClientResolving(String... addresses) {
        return RestClient.builder()
                .requestFactory(TelegramHttpConfig.addressFailoverRequestFactory(
                        resolverAnswering(addresses), Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .baseUrl("http://" + HOSTNAME + ":" + server.getAddress().getPort())
                .build();
    }

    /**
     * A {@link DnsResolver} that answers with exactly the addresses given, in that order, and
     * knows no other name. Written out rather than as a lambda because the interface has a second
     * method - the canonical-name lookup, which this client never uses.
     */
    private static DnsResolver resolverAnswering(String... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return TelegramConnectionFailoverTest.resolve(host, addresses);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static InetAddress[] resolve(String host, String... addresses) throws UnknownHostException {
        if (!HOSTNAME.equals(host)) {
            throw new UnknownHostException(host);
        }
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            resolved[i] = InetAddress.getByName(addresses[i]);
        }
        return resolved;
    }
}
