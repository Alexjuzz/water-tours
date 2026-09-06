package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentServiceTest {
    final OrderRepository orders = mock(OrderRepository.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final ObjectMapper mapper = new ObjectMapper();
    final Map<UUID, Payment> store = new LinkedHashMap<>();
    MockRestServiceServer server;
    PaymentService service;
    Order order;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new PaymentService(orders, payments, builder, transactionManager, mapper,
                "test-shop", "test-secret", "http://localhost:8080");

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("1200.00"));
        order.setStatus(OrderStatus.DRAFT);
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(payments.findAllByOrderId(order.getId())).thenAnswer(invocation -> new ArrayList<>(store.values()));
        when(payments.findOrderId(any())).thenAnswer(invocation -> order.getId());
        when(payments.findById(any())).thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            store.put(p.getId(), p);
            return p;
        });
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getCreatedAt() == null) p.setCreatedAt(Instant.now());
            store.put(p.getId(), p);
            return p;
        });
    }

    private Payment seedPendingPayment(String providerPaymentId) {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setProviderPaymentId(providerPaymentId);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(order.getTotalAmount());
        payment.setCreatedAt(Instant.now());
        store.put(payment.getId(), payment);
        when(payments.findByProviderPaymentId(providerPaymentId)).thenReturn(Optional.of(payment));
        return payment;
    }

    private WebhookRequestDTO notification(String providerId, String event) {
        WebhookRequestDTO request = new WebhookRequestDTO();
        WebhookRequestDTO.ObjectData object = new WebhookRequestDTO.ObjectData();
        object.setId(providerId);
        object.setStatus("succeeded");
        request.setObject(object);
        request.setType("notification");
        request.setEvent(event);
        return request;
    }

    private String providerPaymentJson(String providerId, String status, BigDecimal amount, UUID orderId,
                                        UUID paymentId, boolean paid, String capturedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", providerId);
        root.put("status", status);
        ObjectNode amountNode = root.putObject("amount");
        amountNode.put("value", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        amountNode.put("currency", "RUB");
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("orderId", orderId.toString());
        metadata.put("paymentId", paymentId.toString());
        if (paid) root.put("paid", true);
        if (capturedAt != null) root.put("captured_at", capturedAt);
        return root.toString();
    }

    // The test double derives an ID from the key; real provider IDs are independent.
    private ResponseCreator respondEchoingIdempotenceKey(BigDecimal amount, UUID orderId, String status, String url) {
        return request -> {
            String paymentId = request.getHeaders().getFirst("Idempotence-Key");
            ObjectNode root = mapper.createObjectNode();
            root.put("id", "provider-" + paymentId);
            root.put("status", status);
            ObjectNode amountNode = root.putObject("amount");
            amountNode.put("value", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            amountNode.put("currency", "RUB");
            ObjectNode metadata = root.putObject("metadata");
            metadata.put("orderId", orderId.toString());
            metadata.put("paymentId", paymentId);
            if (url != null) root.putObject("confirmation").put("confirmation_url", url);
            return withSuccess(root.toString(), MediaType.APPLICATION_JSON).createResponse(request);
        };
    }

    @Test
    void firstCreateStoresImmutableRequestAndIdAndReturnsUrl() {
        String url = "https://example.invalid/pay/first";
        server.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount.value").value("1200.00"))
                .andExpect(jsonPath("$.amount.currency").value("RUB"))
                .andExpect(jsonPath("$.capture").value(true))
                .andRespond(respondEchoingIdempotenceKey(order.getTotalAmount(), order.getId(), "pending", url));

        var result = service.startPayment(order.getId());

        Payment stored = store.values().iterator().next();
        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getRequestBody()).isNotBlank().contains(stored.getId().toString());
        assertThat(result.paymentId()).isEqualTo(stored.getId());
        assertThat(result.paymentUrl()).isEqualTo(url);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        server.verify();
    }

    @Test
    void repeatedStartPaymentReturnsExistingWithoutNewHttpCall() {
        String url = "https://example.invalid/pay/repeat";
        server.expect(once(), requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(respondEchoingIdempotenceKey(order.getTotalAmount(), order.getId(), "pending", url));

        var first = service.startPayment(order.getId());
        var second = service.startPayment(order.getId());

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.paymentUrl()).isEqualTo(url);
        assertThat(store).hasSize(1);
        server.verify();
    }

    @Test
    void timeoutThenRetryReusesSameIdempotenceKeyAndBody() {
        server.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> { throw new IOException("timeout"); });

        assertThatThrownBy(() -> service.startPayment(order.getId()))
                .isInstanceOf(PaymentProviderException.class);

        Payment stored = store.values().iterator().next();
        String firstRequestBody = stored.getRequestBody();
        UUID paymentId = stored.getId();
        assertThat(firstRequestBody).isNotNull();

        server.reset();
        String url = "https://example.invalid/pay/retry";
        server.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotence-Key", paymentId.toString()))
                .andExpect(content().string(firstRequestBody))
                .andRespond(respondEchoingIdempotenceKey(order.getTotalAmount(), order.getId(), "pending", url));

        var result = service.startPayment(order.getId());

        assertThat(result.paymentId()).isEqualTo(paymentId);
        assertThat(result.paymentUrl()).isEqualTo(url);
        assertThat(stored.getRequestBody()).isEqualTo(firstRequestBody);
        server.verify();
    }

    @Test
    void pendingProviderResponseIsNotTrustedWhenWebhookReportsSucceeded() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = seedPendingPayment("provider-pending-1");

        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-pending-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(providerPaymentJson("provider-pending-1", "pending",
                        order.getTotalAmount(), order.getId(), payment.getId(), false, null), MediaType.APPLICATION_JSON));

        service.handleWebhook(notification("provider-pending-1", "payment.succeeded"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(orders, never()).save(any());
        server.verify();
    }

    @Test
    void successIsAppliedOnlyOnceForSequentialDuplicateNotifications() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = seedPendingPayment("provider-success-1");
        String capturedAt = Instant.now().toString();

        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-success-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(providerPaymentJson("provider-success-1", "succeeded",
                        order.getTotalAmount(), order.getId(), payment.getId(), true, capturedAt), MediaType.APPLICATION_JSON));

        service.handleWebhook(notification("provider-success-1", "payment.succeeded"));
        service.handleWebhook(notification("provider-success-1", "payment.succeeded"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(orders, times(1)).save(order);
        server.verify();
    }

    @Test
    void mismatchedAmountFailsWithoutMarkingPaid() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = seedPendingPayment("provider-bad-amount");
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-bad-amount"))
                .andRespond(withSuccess(providerPaymentJson("provider-bad-amount", "succeeded",
                        new BigDecimal("1.00"), order.getId(), payment.getId(), true, Instant.now().toString()),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.handleWebhook(notification("provider-bad-amount", "payment.succeeded")))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        server.verify();
    }

    @Test
    void mismatchedCurrencyFailsWithoutMarkingPaid() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = seedPendingPayment("provider-bad-currency");
        String body = providerPaymentJson("provider-bad-currency", "succeeded",
                order.getTotalAmount(), order.getId(), payment.getId(), true, Instant.now().toString())
                .replace("\"RUB\"", "\"USD\"");
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-bad-currency"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.handleWebhook(notification("provider-bad-currency", "payment.succeeded")))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        server.verify();
    }

    @Test
    void mismatchedMetadataFailsWithoutMarkingPaid() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = seedPendingPayment("provider-bad-metadata");
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-bad-metadata"))
                .andRespond(withSuccess(providerPaymentJson("provider-bad-metadata", "succeeded",
                        order.getTotalAmount(), UUID.randomUUID(), payment.getId(), true, Instant.now().toString()),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.handleWebhook(notification("provider-bad-metadata", "payment.succeeded")))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        server.verify();
    }

    @Test
    void providerOutageRaisesPaymentProviderException() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        seedPendingPayment("provider-outage");
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-outage"))
                .andRespond(request -> { throw new IOException("connection reset"); });

        assertThatThrownBy(() -> service.handleWebhook(notification("provider-outage", "payment.succeeded")))
                .isInstanceOf(PaymentProviderException.class);
        server.verify();
    }

    @Test
    void creationOlderThan23HoursMustNotPostAndRequiresManualReconciliation() {
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(order.getTotalAmount());
        payment.setRequestBody("{\"amount\":{\"value\":\"1200.00\",\"currency\":\"RUB\"}}");
        payment.setCreatedAt(Instant.now().minus(Duration.ofHours(24)));
        payment.setNextCheckAt(Instant.now().plusSeconds(60));
        store.put(payment.getId(), payment);

        assertThatThrownBy(() -> service.startPayment(order.getId()))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("manual reconciliation");

        assertThat(payment.getNextCheckAt()).isNull();
        server.verify();
    }
}
