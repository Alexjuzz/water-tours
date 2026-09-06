package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PaymentServiceTest {
    final OrderRepository orders = mock(OrderRepository.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    MockRestServiceServer server;
    PaymentService service;
    Order order;
    Payment payment;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new PaymentService(orders, payments, builder, "test-shop", "test-secret", "http://localhost:8080");
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("1200.00"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setProviderPaymentId("provider-test-id");
        payment.setStatus(PaymentStatus.PENDING);
        when(payments.findByProviderPaymentId("provider-test-id")).thenReturn(Optional.of(payment));
    }

    WebhookRequestDTO notification() {
        WebhookRequestDTO request = new WebhookRequestDTO();
        WebhookRequestDTO.ObjectData object = new WebhookRequestDTO.ObjectData();
        object.setId("provider-test-id");
        object.setStatus("succeeded");
        request.setObject(object);
        request.setType("notification");
        request.setEvent("payment.succeeded");
        return request;
    }

    @Test
    void notificationCannotMarkPendingProviderPaymentAsPaid() {
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-test-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"pending\"}", MediaType.APPLICATION_JSON));
        service.handleWebhook(notification());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(orders, never()).save(any());
        server.verify();
    }

    @Test
    void verifiedSuccessIsAppliedOnlyOnceForSequentialNotifications() {
        server.expect(requestTo("https://api.yookassa.ru/v3/payments/provider-test-id"))
                .andRespond(withSuccess("{\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));
        service.handleWebhook(notification());
        service.handleWebhook(notification());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(orders, times(1)).save(order);
        server.verify();
    }

    @Test
    void createsPaymentFromServerCalculatedTotal() {
        order.setStatus(OrderStatus.DRAFT);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        server.expect(requestTo("https://api.yookassa.ru/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount.value").value("1200.00"))
                .andExpect(jsonPath("$.amount.currency").value("RUB"))
                .andExpect(jsonPath("$.capture").value(true))
                .andRespond(withSuccess("{\"id\":\"created-test-id\",\"status\":\"pending\",\"confirmation\":{\"confirmation_url\":\"https://example.invalid/test-payment\"}}", MediaType.APPLICATION_JSON));
        var result = service.startPayment(order.getId());
        assertThat(result.paymentUrl()).isEqualTo("https://example.invalid/test-payment");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        server.verify();
    }
}