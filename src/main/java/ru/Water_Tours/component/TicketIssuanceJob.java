package ru.Water_Tours.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketService;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "tickets.issuance.enabled", havingValue = "true", matchIfMissing = true)
public class TicketIssuanceJob {
    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceJob.class);
    private final OrderRepository orders;
    private final TicketService tickets;

    public TicketIssuanceJob(OrderRepository orders, TicketService tickets) {
        this.orders = orders;
        this.tickets = tickets;
    }

    // The paid order is the durable work item; after a restart unfinished orders are retried.
    @Scheduled(fixedDelayString = "${tickets.issuance.interval:30000}", initialDelayString = "${tickets.issuance.interval:30000}")
    public void issuePaidOrders() {
        for (UUID id : orders.findPaidOrderIdsAwaitingTickets()) {
            try {
                tickets.issueTickets(id);
            } catch (Exception e) {
                log.warn("Ticket issuance will be retried for orderId={}, errorType={}", id, e.getClass().getSimpleName());
            }
        }
    }
}