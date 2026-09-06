package ru.Water_Tours;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;
import ru.Water_Tours.ticket.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true", "spring.profiles.active=test",
        "yookassa.shopId=test", "yookassa.secretKey=test", "app.base-url=http://localhost:8080",
        "spring.mail.username=test@example.invalid", "spring.mail.password=test",
        "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
        "payments.reconciliation.enabled=false", "order.expiration-check-interval=86400000"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(PaymentPersistenceTest.HttpMock.class)
class PaymentPersistenceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static MockRestServiceServer server;

    @TestConfiguration
    static class HttpMock {
        @Bean("yookassaRestClientBuilder")
        RestClient.Builder builder() {
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            return builder;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PaymentService service;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @org.junit.jupiter.api.BeforeEach
    void isolateTests() {
        server.reset();
        payments.deleteAll();
        orders.deleteAll();
    }

    @Test
    void timeoutSurvivesServiceRecreationAndBackgroundRecoveryUsesSameRequest() throws Exception {
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setEmail("test@example.invalid");
        order = orders.saveAndFlush(order);
        UUID id = order.getId();
        server.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andRespond(withException(new java.io.IOException("response lost")));
        assertThatThrownBy(() -> service.startPayment(id)).isInstanceOf(PaymentProviderException.class);
        var attempt = payments.findAllByOrderId(id).getFirst();
        assertThat(attempt.getRequestBody()).isNotBlank();
        assertThat(attempt.getProviderPaymentId()).isNull();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        server.verify();

        RestClient.Builder builder = RestClient.builder();
        var recoveryServer = MockRestServiceServer.bindTo(builder).build();
        PaymentService restarted = new PaymentService(orders, payments, builder, transactions, mapper,
                "test", "test", "http://changed.example.invalid");
        jdbc.update("update payment set next_check_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), attempt.getId());
        String success = mapper.writeValueAsString(Map.of("id", "recovered-provider", "status", "succeeded",
                "amount", Map.of("value", "100.00", "currency", "RUB"), "paid", true,
                "captured_at", "2026-09-06T10:00:00Z",
                "metadata", Map.of("orderId", id.toString(), "paymentId", attempt.getId().toString())));
        recoveryServer.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(header("Idempotence-Key", attempt.getId().toString()))
                .andExpect(content().string(attempt.getRequestBody()))
                .andRespond(withSuccess(success, MediaType.APPLICATION_JSON));
        restarted.reconcilePendingPayments();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payments.findAllByOrderId(id)).hasSize(1);
        recoveryServer.verify();
    }

    @Test
    void concurrentCreationSharesCommittedAttemptAndLatePaymentSurvivesExpiration() throws Exception {
        server.reset();
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("1200.00"));
        order.setEmail("test@example.invalid");
        order.setCreatedAt(Instant.now().minusSeconds(3600));
        order = orders.saveAndFlush(order);
        UUID orderId = order.getId();
        Set<String> keys = ConcurrentHashMap.newKeySet();
        Set<String> bodies = ConcurrentHashMap.newKeySet();
        CountDownLatch bothRequests = new CountDownLatch(2);
        server.expect(times(2), requestTo("https://api.yookassa.ru/v3/payments")).andRespond(request -> {
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            var json = mapper.readTree(body);
            keys.add(request.getHeaders().getFirst("Idempotence-Key"));
            bodies.add(body);
            assertThat(jdbc.queryForObject("select count(*) from payment where order_id = ?", Long.class, orderId)).isEqualTo(1L);
            bothRequests.countDown();
            try {
                if (!bothRequests.await(10, TimeUnit.SECONDS)) throw new AssertionError("Second concurrent request did not reach provider");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return withSuccess(mapper.writeValueAsString(Map.of(
                    "id", "provider-concurrent", "status", "pending",
                    "amount", Map.of("value", "1200.00", "currency", "RUB"),
                    "metadata", mapper.convertValue(json.get("metadata"), Map.class),
                    "confirmation", Map.of("confirmation_url", "https://example.invalid/pay"))),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> service.startPayment(orderId));
            var two = executor.submit(() -> service.startPayment(orderId));
            assertThat(one.get(20, TimeUnit.SECONDS).paymentId()).isEqualTo(two.get(20, TimeUnit.SECONDS).paymentId());
        }
        assertThat(keys).hasSize(1);
        assertThat(bodies).hasSize(1);
        assertThat(payments.findAllByOrderId(orderId)).hasSize(1);
        server.verify();
        var attempt = payments.findAllByOrderId(orderId).getFirst();
        assertThat(service.startPayment(orderId).paymentId()).isEqualTo(attempt.getId());

        assertThat(orderService.expireIfPending(orderId, Instant.now().minusSeconds(1800))).isTrue();
        server.reset();
        String success = mapper.writeValueAsString(Map.of("id", "provider-concurrent", "status", "succeeded",
                "amount", Map.of("value", "1200.00", "currency", "RUB"), "paid", true,
                "captured_at", "2026-09-06T10:00:00Z",
                "metadata", Map.of("orderId", orderId.toString(), "paymentId", attempt.getId().toString())));
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-concurrent"))
                .andRespond(withSuccess(success, MediaType.APPLICATION_JSON));
        WebhookRequestDTO notification = new WebhookRequestDTO();
        notification.setEvent("payment.succeeded");
        var object = new WebhookRequestDTO.ObjectData();
        object.setId("provider-concurrent");
        notification.setObject(object);
        service.handleWebhook(notification);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orderService.expireIfPending(orderId, Instant.now())).isFalse();
        server.verify();
    }

    @Test
    void unavailableProviderReturns503FromWebhookAndDoesNotLoseAttempt() throws Exception {
        server.reset();
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setEmail("test@example.invalid");
        order = orders.saveAndFlush(order);
        UUID id = order.getId();
        server.expect(requestTo("https://api.yookassa.ru/v3/payments")).andRespond(request -> {
            var json = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            return withSuccess(mapper.writeValueAsString(Map.of("id", "provider-outage", "status", "pending",
                    "amount", Map.of("value", "100.00", "currency", "RUB"),
                    "metadata", mapper.convertValue(json.get("metadata"), Map.class),
                    "confirmation", Map.of("confirmation_url", "https://example.invalid/pay"))),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        service.startPayment(id);
        server.verify();
        server.reset();
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-outage")).andRespond(withServerError());
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"provider-outage\"}}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payments.findAllByOrderId(id)).hasSize(1);
        server.verify();
    }
}
