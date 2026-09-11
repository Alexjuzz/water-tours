package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.time.Clock;
import java.time.Duration;
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

class TicketServiceTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);

    private TicketService serviceWithClock(Instant now, Duration validity) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new TicketService(ticketRepository, orderRepository, validity, clock);
    }

    private Order paidOrder(Instant paidAt, TicketType type, int quantity) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        order.setEmail("buyer@example.com");
        order.setPaidAt(paidAt);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setType(type);
        item.setQuantity(quantity);
        order.setOrderItems(List.of(item));
        return order;
    }

    private Ticket existingTicket(Order order, Instant validFrom, Instant validTo, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setCode(UUID.randomUUID().toString());
        ticket.setOrder(order);
        ticket.setTicketType(TicketType.ADULT);
        ticket.setTicketStatus(status);
        ticket.setPurchaseEmail(order.getEmail());
        ticket.setPurchaseDate(order.getPaidAt());
        ticket.setValidFrom(validFrom);
        ticket.setValidTo(validTo);
        // Redemption re-reads the owning order after locking the ticket row.
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        return ticket;
    }

    @Test
    void redeemByCode_whileARefundIsInFlight_isRejectedAndTicketStaysIssued() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        order.setRefundPendingAt(validFrom.plus(Duration.ofMinutes(5)));
        TicketService service = serviceWithClock(validFrom.plus(Duration.ofMinutes(6)), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refund is being processed");

        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
        assertThat(ticket.getUsedAt()).isNull();
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void redeemByCode_whenOrderAlreadyRefunded_isRejected() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        order.setStatus(OrderStatus.REFUNDED);
        TicketService service = serviceWithClock(validFrom.plus(Duration.ofHours(1)), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refunded order");

        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.ISSUED);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void issueTickets_usesOrderPaidAtForValidityWindow_despiteDelayedIssuance() {
        Instant paidAt = Instant.parse("2026-01-10T10:00:00Z");
        Instant issuedNow = paidAt.plus(Duration.ofHours(2));
        Order order = paidOrder(paidAt, TicketType.ADULT, 1);
        TicketService service = serviceWithClock(issuedNow, Duration.ofHours(72));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(ticketRepository.findAllByOrderId(order.getId())).thenReturn(new ArrayList<>());
        when(ticketRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TicketResponse> tickets = service.issueTickets(order.getId());

        assertThat(tickets).hasSize(1);
        TicketResponse ticket = tickets.get(0);
        assertThat(ticket.purchasedAt()).isEqualTo(paidAt);
        assertThat(ticket.validFrom()).isEqualTo(paidAt);
        assertThat(ticket.validTo()).isEqualTo(paidAt.plus(Duration.ofHours(72)));
    }

    @Test
    void issueTickets_forPrivateBoat_createsOneGroupTicketForSixGuests() {
        Instant paidAt = Instant.parse("2026-01-10T10:00:00Z");
        Order order = paidOrder(paidAt, TicketType.PRIVATE_BOAT, 6);
        order.setOrderType(OrderType.PRIVATE_BOAT);
        order.setBoatGuestCount(6);
        order.setBoatDurationMinutes(120);
        TicketService service = serviceWithClock(paidAt.plusSeconds(10), Duration.ofHours(72));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(ticketRepository.findAllByOrderId(order.getId())).thenReturn(new ArrayList<>());
        when(ticketRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TicketResponse> tickets = service.issueTickets(order.getId());

        assertThat(tickets).singleElement().satisfies(ticket -> {
            assertThat(ticket.ticketType()).isEqualTo(TicketType.PRIVATE_BOAT);
            assertThat(ticket.validFrom()).isEqualTo(paidAt);
            assertThat(ticket.validTo()).isEqualTo(paidAt.plus(Duration.ofHours(72)));
        });
    }

    @Test
    void issueTickets_withConfigured24HourValidity_usesConfiguredDuration() {
        Instant paidAt = Instant.parse("2026-01-10T10:00:00Z");
        Instant issuedNow = paidAt.plusSeconds(30);
        Order order = paidOrder(paidAt, TicketType.CHILD, 1);
        TicketService service = serviceWithClock(issuedNow, Duration.ofHours(24));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(ticketRepository.findAllByOrderId(order.getId())).thenReturn(new ArrayList<>());
        when(ticketRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TicketResponse> tickets = service.issueTickets(order.getId());

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).validFrom()).isEqualTo(paidAt);
        assertThat(tickets.get(0).validTo()).isEqualTo(paidAt.plus(Duration.ofHours(24)));
    }

    @Test
    void issueTickets_forUnpaidOrder_isRejected() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        TicketService service = serviceWithClock(Instant.parse("2026-01-10T12:00:00Z"), Duration.ofHours(72));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.issueTickets(order.getId()))
                .isInstanceOf(IllegalStateException.class);

        verify(ticketRepository, never()).saveAll(any());
    }

    @Test
    void issueTickets_whenTicketsAlreadyExist_returnsExistingWithoutSaveAll() {
        Instant paidAt = Instant.parse("2026-01-10T10:00:00Z");
        Order order = paidOrder(paidAt, TicketType.ADULT, 1);
        Ticket existing = existingTicket(order, paidAt, paidAt.plus(Duration.ofHours(72)), TicketStatus.ISSUED);
        TicketService service = serviceWithClock(paidAt.plusSeconds(10), Duration.ofHours(72));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(ticketRepository.findAllByOrderId(order.getId())).thenReturn(List.of(existing));

        List<TicketResponse> tickets = service.issueTickets(order.getId());

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).code()).isEqualTo(existing.getCode());
        verify(ticketRepository, never()).saveAll(any());
    }

    @Test
    void redeemByCode_atExactValidFrom_isAllowed() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        TicketService service = serviceWithClock(validFrom, Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketResponse response = service.redeemByCode(ticket.getCode());

        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void redeemByCode_oneNanosecondBeforeValidTo_isAllowed() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        TicketService service = serviceWithClock(validTo.minusNanos(1), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketResponse response = service.redeemByCode(ticket.getCode());

        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void redeemByCode_atExactValidTo_isRejected() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        TicketService service = serviceWithClock(validTo, Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(IllegalStateException.class);

        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void redeemByCode_beforeValidFrom_isRejected() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.ISSUED);
        TicketService service = serviceWithClock(validFrom.minusNanos(1), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(IllegalStateException.class);

        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void redeemByCode_whenAlreadyUsed_isRejected() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.USED);
        TicketService service = serviceWithClock(validFrom.plus(Duration.ofHours(1)), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(RuntimeException.class);

        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void redeemByCode_whenExpired_isRejected() {
        Instant validFrom = Instant.parse("2026-01-10T10:00:00Z");
        Instant validTo = validFrom.plus(Duration.ofHours(72));
        Order order = paidOrder(validFrom, TicketType.ADULT, 1);
        Ticket ticket = existingTicket(order, validFrom, validTo, TicketStatus.EXPIRED);
        TicketService service = serviceWithClock(validFrom.plus(Duration.ofHours(1)), Duration.ofHours(72));

        when(ticketRepository.findByCodeForUpdate(ticket.getCode())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.redeemByCode(ticket.getCode()))
                .isInstanceOf(RuntimeException.class);

        verify(ticketRepository, never()).save(any(Ticket.class));
    }
}
