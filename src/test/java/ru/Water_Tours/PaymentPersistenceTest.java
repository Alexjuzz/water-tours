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
        "management.health.mail.enabled=false", "payments.reconciliation.enabled=false", "tickets.issuance.enabled=false", "order.expiration-check-interval=86400000",
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
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

    @Autowired ru.Water_Tours.ticket.repository.TicketRepository ticketRepository;
    @Autowired TicketService ticketService;
    @Autowired TicketEmailService emailService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.mail.javamail.JavaMailSender mailSender;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    PdfTicketService pdfService;

    Order paidOrder() {
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("3000.00"));
        order.setEmail("test@example.invalid");
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        var item = new ru.Water_Tours.ticket.model.OrderItem.OrderItem();
        item.setOrder(order);
        item.setType(ru.Water_Tours.enums.TicketType.ADULT);
        item.setQuantity(2);
        item.setPrice(new BigDecimal("1500.00"));
        item.setAmountPrice(new BigDecimal("3000.00"));
        order.setOrderItems(List.of(item));
        return orders.saveAndFlush(order);
    }

    /**
     * The staff check page reads the ticket's order to show a refund hold. Open-in-view is off, so
     * this only holds together if the read runs inside a transaction; a mocked repository would
     * never notice, which is why it is exercised here against a real database.
     */
    @Test
    void staffCheckPageRendersAndAnnouncesARefundHold() {
        Order order = paidOrder();
        new ru.Water_Tours.component.TicketIssuanceJob(orders, ticketService).issuePaidOrders();
        String code = ticketRepository.findAllByOrderId(order.getId()).getFirst().getCode();

        String page = ticketService.renderCheckPage(code, null, null);
        assertThat(page).contains("Ticket with code").doesNotContain("on hold");

        jdbc.update("update orders set refund_pending_at = now() where id = ?", order.getId());

        assertThat(ticketService.renderCheckPage(code, null, null)).contains("on hold");
        assertThatThrownBy(() -> ticketService.redeemByCode(code))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refund is being processed");
        assertThat(ticketRepository.findByCode(code).orElseThrow().getTicketStatus())
                .isEqualTo(ru.Water_Tours.enums.TicketStatus.ISSUED);

        jdbc.update("update orders set refund_pending_at = null where id = ?", order.getId());
        assertThat(ticketService.redeemByCode(code).ticketStatus())
                .isEqualTo(ru.Water_Tours.enums.TicketStatus.USED);
    }

    @Test
    void automaticIssuanceAndConcurrentRedemptionHappenOnlyOnce() throws Exception {
        Order order = paidOrder();
        var job = new ru.Water_Tours.component.TicketIssuanceJob(orders, ticketService);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(job::issuePaidOrders);
            var second = executor.submit(job::issuePaidOrders);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        var tickets = ticketRepository.findAllByOrderId(order.getId());
        assertThat(tickets).hasSize(2);
        assertThat(tickets).allSatisfy(ticket -> {
            assertThat(ticket.getValidFrom()).isEqualTo(order.getPaidAt());
            assertThat(ticket.getValidTo()).isEqualTo(order.getPaidAt().plusSeconds(72 * 3600));
        });
        new ru.Water_Tours.component.TicketIssuanceJob(orders, ticketService).issuePaidOrders();
        assertThat(ticketRepository.findAllByOrderId(order.getId())).hasSize(2);
        String code = tickets.getFirst().getCode();
        CountDownLatch ready = new CountDownLatch(2);
        Callable<Boolean> redeem = () -> {
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Redeem start timeout");
            try { ticketService.redeemByCode(code); return true; }
            catch (IllegalArgumentException | IllegalStateException expected) { return false; }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(redeem);
            var second = executor.submit(redeem);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(ticketRepository.findByCode(code).orElseThrow().getTicketStatus())
                .isEqualTo(ru.Water_Tours.enums.TicketStatus.USED);
    }

    @Test
    void mailFailureKeepsPaidOrderQueuedAndRetryMarksDelivery() throws Exception {
        Order order = paidOrder();
        new ru.Water_Tours.component.TicketIssuanceJob(orders, ticketService).issuePaidOrders();
        org.mockito.Mockito.when(pdfService.buildTicketsPdfByOrderId(order.getId(), "http://localhost:8080"))
                .thenReturn(new byte[] {37, 80, 68, 70});
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("test SMTP unavailable"))
                .doNothing().when(mailSender).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
        new ru.Water_Tours.component.TicketEmailDeliveryJob(orders, emailService).deliverPendingEmails();
        Order failed = orders.findById(order.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(failed.getTicketsEmailedAt()).isNull();
        assertThat(orders.findOrderIdsAwaitingTicketEmail()).contains(order.getId());
        var restartedJob = new ru.Water_Tours.component.TicketEmailDeliveryJob(orders, emailService);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(restartedJob::deliverPendingEmails);
            var second = executor.submit(restartedJob::deliverPendingEmails);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        restartedJob.deliverPendingEmails();
        assertThat(orders.findById(order.getId()).orElseThrow().getTicketsEmailedAt()).isNotNull();
        assertThat(orders.findOrderIdsAwaitingTicketEmail()).doesNotContain(order.getId());
        org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.times(2))
                .send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void concurrentSuccessNotificationsKeepOneFinalState() throws Exception {
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setEmail("test@example.invalid");
        order = orders.saveAndFlush(order);
        UUID id = order.getId();
        server.expect(requestTo("https://api.yookassa.ru/v3/payments")).andRespond(request -> {
            var json = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            return withSuccess(mapper.writeValueAsString(Map.of("id", "concurrent-webhook", "status", "pending",
                    "amount", Map.of("value", "100.00", "currency", "RUB"),
                    "metadata", mapper.convertValue(json.get("metadata"), Map.class),
                    "confirmation", Map.of("confirmation_url", "https://example.invalid/pay"))),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        service.startPayment(id);
        var payment = payments.findAllByOrderId(id).getFirst();
        server.verify();
        server.reset();
        CountDownLatch requests = new CountDownLatch(2);
        String success = mapper.writeValueAsString(Map.of("id", "concurrent-webhook", "status", "succeeded",
                "amount", Map.of("value", "100.00", "currency", "RUB"), "paid", true,
                "captured_at", "2026-09-06T10:00:00Z",
                "metadata", Map.of("orderId", id.toString(), "paymentId", payment.getId().toString())));
        server.expect(times(2), requestTo("https://api.yookassa.ru/v3/payments/concurrent-webhook"))
                .andRespond(request -> {
                    requests.countDown();
                    try {
                        if (!requests.await(10, TimeUnit.SECONDS)) throw new AssertionError("Second webhook did not reach provider");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return withSuccess(success, MediaType.APPLICATION_JSON).createResponse(request);
                });
        WebhookRequestDTO notification = new WebhookRequestDTO();
        notification.setEvent("payment.succeeded");
        var object = new WebhookRequestDTO.ObjectData();
        object.setId("concurrent-webhook");
        notification.setObject(object);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.handleWebhook(notification));
            var second = executor.submit(() -> service.handleWebhook(notification));
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(ru.Water_Tours.enums.PaymentStatus.SUCCEEDED);
            assertThat(second.get(20, TimeUnit.SECONDS).status()).isEqualTo(ru.Water_Tours.enums.PaymentStatus.SUCCEEDED);
        }
        assertThat(payments.findAllByOrderId(id)).hasSize(1);
        assertThat(orders.findById(id).orElseThrow().getPaidAt()).isEqualTo(Instant.parse("2026-09-06T10:00:00Z"));
        server.verify();
    }
}
