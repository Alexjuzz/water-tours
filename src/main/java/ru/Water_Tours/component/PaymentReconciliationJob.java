package ru.Water_Tours.component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.ticket.service.PaymentService;

@Component
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconciliationJob {
    private final PaymentService payments;
    public PaymentReconciliationJob(PaymentService payments) { this.payments = payments; }

    @Scheduled(fixedDelayString = "${payments.reconciliation.interval:60000}", initialDelayString = "${payments.reconciliation.interval:60000}")
    public void reconcile() { payments.reconcilePendingPayments(); }
}
