package ru.Water_Tours.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketEmailService;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "tickets.email-delivery.enabled", havingValue = "true")
public class TicketEmailDeliveryJob {
    private static final Logger log = LoggerFactory.getLogger(TicketEmailDeliveryJob.class);
    private final OrderRepository orders;
    private final TicketEmailService email;
    public TicketEmailDeliveryJob(OrderRepository orders, TicketEmailService email) {
        this.orders = orders;
        this.email = email;
    }
    @Scheduled(fixedDelayString = "${tickets.email-delivery.interval:60000}", initialDelayString = "${tickets.email-delivery.interval:60000}")
    public void deliverPendingEmails() {
        for (UUID id : orders.findOrderIdsAwaitingTicketEmail()) {
            try {
                email.sendTicketsPdf(id);
            } catch (Exception e) {
                log.warn("Ticket email will be retried for orderId={}, errorType={}", id, e.getClass().getSimpleName());
            }
        }
    }
}