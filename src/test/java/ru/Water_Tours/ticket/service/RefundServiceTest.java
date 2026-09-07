package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RefundServiceTest {
    final OrderRepository orders = mock(OrderRepository.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final TicketRepository tickets = mock(TicketRepository.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final ObjectMapper mapper = new ObjectMapper();
    MockRestServiceServer server;
    RefundService service;
    Order order;
    Payment payment;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RefundService(orders, payments, tickets, builder, transactionManager, mapper,
                "test-shop", "test-secret");

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("1500.00"));
        order.setStatus(OrderStatus.PAID);
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setProviderPaymentId("provider-payment-1");
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setAmount(order.getTotalAmount());
        payment.setCreatedAt(Instant.now());
        when(payments.findAllByOrderId(order.getId())).thenAnswer(i -> new ArrayList<>(List.of(payment)));
        when(payments.findById(payment.getId())).thenAnswer(i -> Optional.of(payment));
        when(payments.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        when(tickets.findAllByOrderId(order.getId())).thenAnswer(i -> new ArrayList<>(List.of(issuedTicket())));
        when(tickets.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Ticket issuedTicket() {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrder(order);
        t.setTicketType(TicketType.ADULT);
        t.setTicketStatus(TicketStatus.ISSUED);
        t.setCode(UUID.randomUUID().toString());
        return t;
    }

    @Test
    void successfulRefundUpdatesPaymentOrderAndRevokesTickets() {
        server.expect(requestTo("https://api.yookassa.ru/v3/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotence-Key", "refund:" + payment.getId()))
                .andExpect(jsonPath("$.payment_id").value("provider-payment-1"))
                .andExpect(jsonPath("$.amount.value").value("1500.00"))
                .andRespond(withSuccess(
                        "{\"id\":\"refund-1\",\"payment_id\":\"provider-payment-1\",\"status\":\"succeeded\"}",
                        MediaType.APPLICATION_JSON));

        var result = service.refund(order.getId());

        assertThat(result.providerRefundId()).isEqualTo("refund-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProviderRefundId()).isEqualTo("refund-1");
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("1500.00");
        assertThat(payment.getRefundedAt()).isNotNull();
        server.verify();
    }

    @Test
    void refundIsRejectedWhenATicketAlreadyUsedAndMakesNoHttpCall() {
        Ticket used = issuedTicket();
        used.setTicketStatus(TicketStatus.USED);
        when(tickets.findAllByOrderId(order.getId())).thenReturn(List.of(used));

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been used");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        server.verify();
    }

    @Test
    void refundIsRejectedWhenOrderIsNotPaid() {
        order.setStatus(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID order");
        server.verify();
    }

    @Test
    void refundIsRejectedWhenNoSucceededPaymentExists() {
        payment.setStatus(PaymentStatus.REFUNDED);

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No succeeded payment");
        server.verify();
    }

    @Test
    void providerRejectingRefundLeavesOrderAndPaymentUntouched() {
        server.expect(requestTo("https://api.yookassa.ru/v3/refunds"))
                .andRespond(withSuccess("{\"id\":\"refund-2\",\"payment_id\":\"provider-payment-1\",\"status\":\"canceled\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        server.verify();
    }
}
