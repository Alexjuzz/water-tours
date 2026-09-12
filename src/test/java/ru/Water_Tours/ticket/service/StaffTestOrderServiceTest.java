package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.OrderType;
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
    private final TicketEmailService ticketEmailService = mock(TicketEmailService.class);
    private final StaffTestOrderService service = new StaffTestOrderService(
            orderService, orderRepository, paymentRepository, ticketService, ticketEmailService,
            Clock.fixed(NOW, ZoneOffset.UTC));

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
        Order result = service.createIssuedTestOrder("staff", false, null);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.getTestPaid()).isTrue();
        assertThat(result.getPaidAt()).isEqualTo(NOW);
        verify(ticketService).issueTickets(order.getId());
    }

    @Test
    void leavesDeliveryStateHonestSoNothingIsMarkedAsSentWhenItWasNot() {
        Order result = service.createIssuedTestOrder("staff", false, null);

        // Test orders are kept out of automatic delivery by the queue's test_paid exclusion, not by
        // pretending a mail already went out.
        assertThat(result.getTicketsEmailedAt()).isNull();
        verifyNoInteractions(ticketEmailService);
    }

    @Test
    void withoutAnAddressItUsesAnUnroutableTestMailbox() {
        service.createIssuedTestOrder("staff", false, "  ");

        verify(orderService).createOrder(argThat(dto ->
                dto.email().endsWith(StaffTestOrderService.TEST_EMAIL_DOMAIN)), anyString());
    }

    @Test
    void usesTheAddressTheStaffMemberTyped() {
        service.createIssuedTestOrder("staff", false, " owner@example.org ");

        verify(orderService).createOrder(argThat(dto -> dto.email().equals("owner@example.org")), anyString());
    }

    @Test
    void canCreateAPrivateBoatTestOrder() {
        service.createIssuedTestOrder("staff", true, null);

        verify(orderService).createOrder(argThat(dto ->
                dto.tickets() == null && dto.boatRental() != null && dto.boatRental().durationMinutes() == 60), anyString());
    }

    @Test
    void refusesToTestPayAnOrderThatHasPaymentAttempts() {
        when(paymentRepository.findAllByOrderId(order.getId())).thenReturn(List.of(new Payment()));

        assertThatThrownBy(() -> service.createIssuedTestOrder("staff", false, null))
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
    void sendTestEmailRefusesTheUnroutableServiceAddress() {
        order.setTestPaid(true);
        order.setEmail("staff-test-x" + StaffTestOrderService.TEST_EMAIL_DOMAIN);

        assertThatThrownBy(() -> service.sendTestEmail(order.getId(), "staff"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ticketEmailService);
    }

    @Test
    void sendTestEmailUsesFirstDeliveryThenResend() {
        order.setTestPaid(true);
        order.setEmail("owner@example.org");

        service.sendTestEmail(order.getId(), "staff");
        verify(ticketEmailService).sendTicketsPdf(order.getId());

        order.setTicketsEmailedAt(NOW);
        service.sendTestEmail(order.getId(), "staff");
        verify(ticketEmailService).resendTicketsPdf(order.getId());
    }

    @Test
    void sendTestEmailRefusesAnOrderThatIsNotATestOrder() {
        Order real = new Order();
        real.setId(UUID.randomUUID());
        real.setTestPaid(false);
        real.setEmail("customer@example.org");
        real.setOrderType(OrderType.PASSENGER);
        when(orderRepository.findById(real.getId())).thenReturn(Optional.of(real));

        assertThatThrownBy(() -> service.sendTestEmail(real.getId(), "staff"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ticketEmailService);
    }
}
