package ru.Water_Tours.component;

import org.junit.jupiter.api.Test;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketEmailService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketEmailDeliveryJobTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final TicketEmailService email = mock(TicketEmailService.class);

    @Test
    void deliversNothingWhenNoStartDateIsConfigured() {
        new TicketEmailDeliveryJob(orders, email, "").deliverPendingEmails();

        // The backlog must stay held: enabling the flag alone must not release it.
        verifyNoInteractions(email);
        verify(orders, never()).findOrderIdsAwaitingTicketEmail();
        verify(orders, never()).findOrderIdsAwaitingTicketEmailPaidAfter(any());
    }

    @Test
    void deliversNothingWhenTheStartDateIsUnparseable() {
        new TicketEmailDeliveryJob(orders, email, "not-a-date").deliverPendingEmails();

        verifyNoInteractions(email);
        verify(orders, never()).findOrderIdsAwaitingTicketEmailPaidAfter(any());
    }

    @Test
    void deliversOnlyOrdersPaidAfterTheConfiguredStart() {
        Instant cutoff = Instant.parse("2026-09-12T18:00:00Z");
        UUID fresh = UUID.randomUUID();
        when(orders.findOrderIdsAwaitingTicketEmailPaidAfter(cutoff)).thenReturn(List.of(fresh));

        new TicketEmailDeliveryJob(orders, email, " 2026-09-12T18:00:00Z ").deliverPendingEmails();

        verify(orders).findOrderIdsAwaitingTicketEmailPaidAfter(cutoff);
        verify(orders, never()).findOrderIdsAwaitingTicketEmail();
        verify(email).sendTicketsPdf(fresh);
    }

    @Test
    void oneFailingOrderDoesNotStopTheRest() {
        Instant cutoff = Instant.parse("2026-09-12T18:00:00Z");
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(orders.findOrderIdsAwaitingTicketEmailPaidAfter(cutoff)).thenReturn(List.of(failing, next));
        doThrow(new RuntimeException("smtp")).when(email).sendTicketsPdf(failing);

        new TicketEmailDeliveryJob(orders, email, cutoff.toString()).deliverPendingEmails();

        verify(email).sendTicketsPdf(next);
    }
}
