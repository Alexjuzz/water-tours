package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StaffTestOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    private final OrderService orderService = mock(OrderService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final StaffTestOrderService service = new StaffTestOrderService(
            orderService, orderRepository, paymentRepository, ticketService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.DRAFT);
        when(orderService.createOrder(any(OrderRequestDTO.class), anyString())).thenReturn(order);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.findAllByOrderId(order.getId())).thenReturn(List.of());
    }

    @Test
    void createsPaidTestOrderAndIssuesTickets() {
        Order result = service.createIssuedTestOrder("staff");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.getTestPaid()).isTrue();
        assertThat(result.getPaidAt()).isEqualTo(NOW);
        verify(ticketService).issueTickets(order.getId());
    }

    @Test
    void suppressesTicketEmailByPreStampingDeliveredAt() {
        Order result = service.createIssuedTestOrder("staff");

        // findOrderIdsAwaitingTicketEmail only returns orders whose ticketsEmailedAt is null, so a
        // non-null stamp is what keeps the delivery job from mailing a test ticket to anyone.
        assertThat(result.getTicketsEmailedAt()).isEqualTo(NOW);
    }

    @Test
    void usesAnUnroutableTestEmailAddress() {
        service.createIssuedTestOrder("staff");

        verify(orderService).createOrder(argThat(dto ->
                dto.email().endsWith(StaffTestOrderService.TEST_EMAIL_DOMAIN)), anyString());
    }

    @Test
    void refusesToTestPayAnOrderThatHasPaymentAttempts() {
        when(paymentRepository.findAllByOrderId(order.getId())).thenReturn(List.of(new Payment()));

        assertThatThrownBy(() -> service.createIssuedTestOrder("staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment attempts");
        verifyNoInteractions(ticketService);
    }

    @Test
    void requireTestOrderRejectsARealCustomerOrder() {
        Order real = new Order();
        real.setId(UUID.randomUUID());
        real.setTestPaid(false);
        when(orderRepository.findById(real.getId())).thenReturn(Optional.of(real));

        assertThatThrownBy(() -> service.requireTestOrder(real.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a test order");
    }

    @Test
    void requireTestOrderAcceptsATestOrder() {
        order.setTestPaid(true);

        assertThat(service.requireTestOrder(order.getId()).getId()).isEqualTo(order.getId());
    }
}
