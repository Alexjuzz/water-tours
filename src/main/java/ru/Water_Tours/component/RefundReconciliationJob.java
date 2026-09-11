package ru.Water_Tours.component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.ticket.service.RefundService;

// A refund whose provider outcome never reached the database is the durable work item here:
// after a crash or a failed commit the marker is still on the payment and this job resolves it.
@Component
@ConditionalOnProperty(name = "refunds.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class RefundReconciliationJob {
    private final RefundService refunds;

    public RefundReconciliationJob(RefundService refunds) {
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${refunds.reconciliation.interval:60000}",
            initialDelayString = "${refunds.reconciliation.interval:60000}")
    public void reconcile() {
        refunds.reconcilePendingRefunds();
    }
}
