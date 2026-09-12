package ru.Water_Tours.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketEmailService;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic first delivery of ticket PDFs.
 *
 * Enabling the flag alone is deliberately not enough: a start date must also be configured, and
 * only orders paid after it are delivered. Delivery was off for days while a backlog of paid
 * orders accumulated, and that backlog contains addresses nobody has reviewed - turning the flag
 * on without a watermark would mail all of them at once. Orders before the watermark stay exactly
 * as they are (queued, not delivered, not marked) for explicit review at /staff/mail-queue.
 */
@Component
@ConditionalOnProperty(name = "tickets.email-delivery.enabled", havingValue = "true")
public class TicketEmailDeliveryJob {
    private static final Logger log = LoggerFactory.getLogger(TicketEmailDeliveryJob.class);

    private final OrderRepository orders;
    private final TicketEmailService email;
    private final String deliverPaidFrom;
    private final AtomicBoolean misconfigurationLogged = new AtomicBoolean(false);

    public TicketEmailDeliveryJob(OrderRepository orders, TicketEmailService email,
                                  @Value("${tickets.email-delivery.deliver-paid-from:}") String deliverPaidFrom) {
        this.orders = orders;
        this.email = email;
        this.deliverPaidFrom = deliverPaidFrom;
    }

    @Scheduled(fixedDelayString = "${tickets.email-delivery.interval:60000}", initialDelayString = "${tickets.email-delivery.interval:60000}")
    public void deliverPendingEmails() {
        Instant paidFrom = deliverPaidFrom();
        if (paidFrom == null) {
            // Held rather than sent: an unusable watermark must never widen the queue.
            if (misconfigurationLogged.compareAndSet(false, true)) {
                log.warn("Ticket email delivery is enabled but tickets.email-delivery.deliver-paid-from is missing or"
                        + " unparseable, so nothing is delivered automatically. Set it to an ISO-8601 instant.");
            }
            return;
        }

        for (UUID id : orders.findOrderIdsAwaitingTicketEmailPaidAfter(paidFrom)) {
            try {
                email.sendTicketsPdf(id);
            } catch (Exception e) {
                log.warn("Ticket email will be retried for orderId={}, errorType={}", id, e.getClass().getSimpleName());
            }
        }
    }

    private Instant deliverPaidFrom() {
        if (deliverPaidFrom == null || deliverPaidFrom.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(deliverPaidFrom.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
