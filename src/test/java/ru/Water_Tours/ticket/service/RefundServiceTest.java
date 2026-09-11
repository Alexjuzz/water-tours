package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RefundServiceTest {
    static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    final OrderRepository orders = mock(OrderRepository.class);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final TicketRepository tickets = mock(TicketRepository.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    final ObjectMapper mapper = new ObjectMapper();
    MockRestServiceServer server;
    RefundService service;
    Order order;
    Payment payment;
    Ticket ticket;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RefundService(orders, payments, tickets, builder, transactionManager, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), "test-shop", "test-secret");

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

        ticket = issuedTicket();
        when(tickets.findAllByOrderId(order.getId())).thenAnswer(i -> new ArrayList<>(List.of(ticket)));
        when(tickets.findAllByOrderIdForUpdate(order.getId())).thenAnswer(i -> new ArrayList<>(List.of(ticket)));
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

    private void expectRefundPost(org.springframework.test.web.client.ResponseCreator response) {
        server.expect(requestTo("https://api.yookassa.ru/v3/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotence-Key", "refund:" + payment.getId()))
                .andRespond(response);
    }

    private void expectRefundLookup(String body) {
        server.expect(requestTo("https://api.yookassa.ru/v3/refunds?payment_id=provider-payment-1&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
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
        assertThat(order.getRefundPendingAt()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProviderRefundId()).isEqualTo("refund-1");
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("1500.00");
        assertThat(payment.getRefundedAt()).isEqualTo(NOW);
        assertThat(payment.getRefundNextCheckAt()).isNull();
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.REVOKED);
        server.verify();
    }

    @Test
    void refundLocksEveryTicketOfTheOrderBeforeDeciding() {
        expectRefundPost(withSuccess("{\"id\":\"refund-1\",\"payment_id\":\"provider-payment-1\",\"status\":\"succeeded\"}",
                MediaType.APPLICATION_JSON));

        service.refund(order.getId());

        verify(tickets, atLeastOnce()).findAllByOrderIdForUpdate(order.getId());
        server.verify();
    }

    @Test
    void refundIsRejectedWhenATicketAlreadyUsedAndMakesNoHttpCall() {
        ticket.setTicketStatus(TicketStatus.USED);

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been used");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getRefundPendingAt()).isNull();
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
    void secondRefundWhileOneIsInFlightIsRejectedWithoutCallingTheProvider() {
        payment.setRefundRequestedAt(NOW.minusSeconds(30));

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        server.verify();
    }

    @Test
    void providerRejectingRefundReleasesTheOrderOnlyAfterConfirmingNoRefundExists() {
        expectRefundPost(withStatus(HttpStatus.BAD_REQUEST).body("{\"type\":\"error\"}").contentType(MediaType.APPLICATION_JSON));
        expectRefundLookup("{\"type\":\"list\",\"items\":[]}");

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("rejected");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getRefundPendingAt()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getRefundFailedAt()).isEqualTo(NOW);
        assertThat(payment.getRefundNextCheckAt()).isNull();
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
        server.verify();
    }

    @Test
    void providerRejectionThatActuallyRefundedIsAppliedLocallyInsteadOfReleasingTheOrder() {
        expectRefundPost(withStatus(HttpStatus.BAD_REQUEST).body("{\"type\":\"error\"}").contentType(MediaType.APPLICATION_JSON));
        expectRefundLookup("{\"type\":\"list\",\"items\":[{\"id\":\"refund-9\",\"payment_id\":\"provider-payment-1\",\"status\":\"succeeded\"}]}");

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(payment.getProviderRefundId()).isEqualTo("refund-9");
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.REVOKED);
        server.verify();
    }

    @Test
    void unreachableProviderLeavesTheOrderOnHoldForReconciliation() {
        expectRefundPost(withException(new java.io.IOException("connection reset")));

        assertThatThrownBy(() -> service.refund(order.getId()))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("unknown");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getRefundPendingAt()).isEqualTo(NOW);
        assertThat(payment.getRefundRequestedAt()).isEqualTo(NOW);
        assertThat(payment.getRefundFailedAt()).isNull();
        assertThat(payment.getRefundNextCheckAt()).isNotNull();
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
        server.verify();
    }

    @Test
    void reconciliationCompletesARefundThatSucceededAtTheProviderButWasNeverStoredLocally() {
        // State left behind by a crash between the provider call and the local commit.
        payment.setRefundRequestedAt(NOW.minusSeconds(300));
        payment.setRefundNextCheckAt(NOW.minusSeconds(60));
        order.setRefundPendingAt(NOW.minusSeconds(300));
        when(payments.findRefundIdsAwaitingReconciliation(NOW)).thenReturn(List.of(payment.getId()));
        expectRefundLookup("{\"type\":\"list\",\"items\":[{\"id\":\"refund-7\",\"payment_id\":\"provider-payment-1\",\"status\":\"succeeded\"}]}");

        service.reconcilePendingRefunds();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProviderRefundId()).isEqualTo("refund-7");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getRefundPendingAt()).isNull();
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.REVOKED);
        server.verify();
    }

    @Test
    void reconciliationReleasesTheOrderWhenTheProviderKnowsOfNoRefund() {
        payment.setRefundRequestedAt(NOW.minusSeconds(300));
        payment.setRefundNextCheckAt(NOW.minusSeconds(60));
        order.setRefundPendingAt(NOW.minusSeconds(300));
        when(payments.findRefundIdsAwaitingReconciliation(NOW)).thenReturn(List.of(payment.getId()));
        expectRefundLookup("{\"type\":\"list\",\"items\":[]}");

        service.reconcilePendingRefunds();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getRefundFailedAt()).isEqualTo(NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getRefundPendingAt()).isNull();
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
        server.verify();
    }

    @Test
    void reconciliationKeepsTheHoldWhenTheProviderCannotBeReached() {
        payment.setRefundRequestedAt(NOW.minusSeconds(300));
        payment.setRefundNextCheckAt(NOW.minusSeconds(60));
        order.setRefundPendingAt(NOW.minusSeconds(300));
        when(payments.findRefundIdsAwaitingReconciliation(NOW)).thenReturn(List.of(payment.getId()));
        server.expect(requestTo("https://api.yookassa.ru/v3/refunds?payment_id=provider-payment-1&limit=100"))
                .andRespond(withException(new java.io.IOException("connection reset")));

        service.reconcilePendingRefunds();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getRefundFailedAt()).isNull();
        assertThat(payment.getRefundCheckAttempts()).isEqualTo(1);
        assertThat(payment.getRefundNextCheckAt()).isAfter(NOW);
        assertThat(order.getRefundPendingAt()).isNotNull();
        server.verify();
    }

    @Test
    void reconciliationSkipsARefundThatWasAlreadyResolved() {
        payment.setRefundRequestedAt(NOW.minusSeconds(300));
        payment.setRefundedAt(NOW.minusSeconds(200));
        when(payments.findRefundIdsAwaitingReconciliation(NOW)).thenReturn(List.of(payment.getId()));

        service.reconcilePendingRefunds();

        server.verify();
    }
}
