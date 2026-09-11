package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff-triggered only: a customer never calls this directly.
 *
 * <p>A refund is three steps, not one: mark the intent locally, ask the provider, record the
 * outcome. The marker written in step one is what makes the other two safe.
 * <ul>
 *   <li>It is written while the order row and every ticket row of the order are locked, so a
 *       redemption running at the same moment either finishes first (and the refund is refused)
 *       or waits and then sees the marker (and the redemption is refused). Money back plus a
 *       boarded walk is the exact outcome this prevents.</li>
 *   <li>It survives a crash or a failed commit between a successful provider refund and the
 *       local write. {@link #reconcilePendingRefunds()} then asks the provider what actually
 *       happened and finishes, or releases, the order.</li>
 * </ul>
 * An uncertain refund keeps the ticket unusable on purpose: the provider is the only authority on
 * whether money moved, and reconciliation keeps asking until it answers.
 */
@Service
public class RefundService {
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    // The provider read timeout is 35s; a first re-check well past that avoids racing our own
    // in-flight request and reading a refund list that has not caught up yet.
    private static final Duration FIRST_CHECK_DELAY = Duration.ofMinutes(2);
    private static final Duration MAX_CHECK_DELAY = Duration.ofHours(1);
    private static final int MAX_RECONCILED_PER_RUN = 50;

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final TicketRepository tickets;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(OrderRepository orders, PaymentRepository payments, TicketRepository tickets,
                         @Qualifier("yookassaRestClientBuilder") RestClient.Builder builder,
                         PlatformTransactionManager transactions, ObjectMapper mapper, Clock clock,
                         @Value("${yookassa.shopId}") String shopId,
                         @Value("${yookassa.secretKey}") String secretKey) {
        this.orders = orders;
        this.payments = payments;
        this.tickets = tickets;
        this.mapper = mapper;
        this.clock = clock;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        client = builder.clone()
                .baseUrl("https://api.yookassa.ru/v3")
                .defaultHeaders(h -> h.setBasicAuth(shopId, secretKey)).build();
    }

    public RefundResult refund(UUID orderId) {
        Prepared prepared = tx.execute(status -> prepare(orderId));
        JsonNode provider;
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "payment_id", prepared.providerPaymentId(),
                    "amount", Map.of("value", prepared.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency", "RUB")));
            provider = client.post().uri("/refunds").contentType(MediaType.APPLICATION_JSON)
                    // Stable per-payment key: a retry after a crash reuses the same refund
                    // instead of creating a second one within YooKassa's 24h dedup window.
                    .header("Idempotence-Key", idempotenceKey(prepared.paymentId()))
                    .body(body).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException rejected) {
            // A 4xx means the provider refused the request itself. Confirm against the refund list
            // before releasing the order, so a misread rejection cannot unblock a refunded ticket.
            throw releaseIfProviderHasNoRefund(prepared, "provider rejected the refund request", rejected);
        } catch (Exception e) {
            throw new PaymentProviderException(
                    "Refund result is unknown and will be reconciled with the provider", e);
        }

        String refundId = succeededRefundId(provider, prepared.providerPaymentId());
        if (refundId != null) return finalizeRefund(prepared, refundId);

        if (provider != null && "canceled".equals(provider.path("status").asText()))
            throw releaseIfProviderHasNoRefund(prepared, "provider canceled the refund", null);

        // pending, or a response we cannot trust: leave the marker and let reconciliation decide.
        throw new PaymentProviderException("Refund was not confirmed as succeeded by provider");
    }

    /** Re-checks refunds whose provider outcome was never recorded locally. */
    public void reconcilePendingRefunds() {
        List<UUID> due = tx.execute(status -> payments
                .findRefundIdsAwaitingReconciliation(Instant.now(clock))
                .stream().limit(MAX_RECONCILED_PER_RUN).toList());
        if (due == null) return;
        for (UUID paymentId : due) {
            try {
                reconcileRefund(paymentId);
            } catch (Exception e) {
                log.warn("Refund reconciliation failed for paymentId={}, errorType={}", paymentId, e.getClass().getSimpleName());
            }
        }
    }

    private void reconcileRefund(UUID paymentId) {
        Prepared prepared = tx.execute(status -> {
            Payment payment = payments.findById(paymentId).orElseThrow();
            if (payment.getRefundedAt() != null || payment.getRefundFailedAt() != null) return null;
            UUID orderId = payment.getOrder().getId();
            orders.findByIdForUpdate(orderId).orElseThrow();
            int attempts = payment.getRefundCheckAttempts() == null ? 0 : payment.getRefundCheckAttempts();
            payment.setRefundCheckAttempts(attempts + 1);
            payment.setRefundNextCheckAt(Instant.now(clock).plus(backoff(attempts + 1)));
            payments.save(payment);
            return new Prepared(orderId, payment.getId(), payment.getProviderPaymentId(), payment.getAmount());
        });
        if (prepared == null) return;

        Optional<String> refundId = lookupSucceededRefund(prepared.providerPaymentId());
        if (refundId.isPresent()) {
            finalizeRefund(prepared, refundId.get());
            log.info("Refund reconciled as succeeded for paymentId={}", prepared.paymentId());
            return;
        }
        // The provider answered and knows of no refund for this payment: no money moved.
        markFailed(prepared, "provider reports no refund for this payment");
        log.warn("Refund reconciled as not performed for paymentId={}, order released", prepared.paymentId());
    }

    private Prepared prepare(UUID orderId) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found"));
        if (order.getStatus() != OrderStatus.PAID)
            throw new IllegalStateException("Only a PAID order can be refunded");
        // Locking the tickets here is what serialises this decision against a redemption.
        List<Ticket> ticketList = tickets.findAllByOrderIdForUpdate(orderId);
        if (ticketList.stream().anyMatch(t -> t.getTicketStatus() == TicketStatus.USED))
            throw new IllegalStateException("Cannot refund: at least one ticket has already been used");
        Payment payment = payments.findAllByOrderId(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No succeeded payment found for this order"));
        if (isRefundInFlight(payment))
            throw new IllegalStateException("A refund for this order is already in progress");
        if (payment.getProviderPaymentId() == null || payment.getProviderPaymentId().isBlank())
            throw new IllegalStateException("Payment has no provider identifier and cannot be refunded");

        Instant now = Instant.now(clock);
        payment.setRefundRequestedAt(now);
        payment.setRefundFailedAt(null);
        payment.setRefundFailureReason(null);
        payment.setRefundCheckAttempts(0);
        payment.setRefundNextCheckAt(now.plus(FIRST_CHECK_DELAY));
        payments.save(payment);
        order.setRefundPendingAt(now);
        orders.save(order);
        return new Prepared(orderId, payment.getId(), payment.getProviderPaymentId(), payment.getAmount());
    }

    private RefundResult finalizeRefund(Prepared prepared, String refundId) {
        return tx.execute(status -> {
            Order order = orders.findByIdForUpdate(prepared.orderId()).orElseThrow();
            Payment payment = payments.findById(prepared.paymentId()).orElseThrow();
            if (payment.getStatus() == PaymentStatus.REFUNDED)
                return new RefundResult(order.getId(), payment.getId(), payment.getProviderRefundId(), payment.getRefundedAmount());
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setProviderRefundId(refundId);
            payment.setRefundedAmount(prepared.amount());
            payment.setRefundedAt(Instant.now(clock));
            payment.setRefundNextCheckAt(null);
            payment.setRefundFailedAt(null);
            payment.setRefundFailureReason(null);
            payments.save(payment);
            order.setStatus(OrderStatus.REFUNDED);
            order.setRefundPendingAt(null);
            orders.save(order);
            List<Ticket> ticketList = tickets.findAllByOrderIdForUpdate(prepared.orderId());
            for (Ticket t : ticketList) {
                if (t.getTicketStatus() == TicketStatus.ISSUED) t.setTicketStatus(TicketStatus.REVOKED);
                else if (t.getTicketStatus() == TicketStatus.USED)
                    // Only reachable if a redemption slipped through; the money is already back,
                    // so record it loudly instead of pretending the refund did not happen.
                    log.error("Refunded order {} contains a ticket that was already used, manual review required", prepared.orderId());
            }
            tickets.saveAll(ticketList);
            return new RefundResult(order.getId(), payment.getId(), refundId, prepared.amount());
        });
    }

    private void markFailed(Prepared prepared, String reason) {
        tx.executeWithoutResult(status -> {
            Order order = orders.findByIdForUpdate(prepared.orderId()).orElseThrow();
            Payment payment = payments.findById(prepared.paymentId()).orElseThrow();
            if (payment.getStatus() == PaymentStatus.REFUNDED) return;
            payment.setRefundFailedAt(Instant.now(clock));
            payment.setRefundFailureReason(reason.length() > 300 ? reason.substring(0, 300) : reason);
            payment.setRefundNextCheckAt(null);
            payments.save(payment);
            order.setRefundPendingAt(null);
            orders.save(order);
        });
    }

    /**
     * Releases the order only when the provider itself confirms no refund exists for the payment.
     * If the provider cannot be reached the order stays pending for reconciliation.
     */
    private PaymentProviderException releaseIfProviderHasNoRefund(Prepared prepared, String reason, Exception cause) {
        Optional<String> existing;
        try {
            existing = lookupSucceededRefund(prepared.providerPaymentId());
        } catch (Exception lookupFailed) {
            return new PaymentProviderException("Refund result is unknown and will be reconciled with the provider",
                    cause != null ? cause : lookupFailed);
        }
        if (existing.isPresent()) {
            finalizeRefund(prepared, existing.get());
            log.warn("Refund request reported an error but a succeeded refund exists for paymentId={}", prepared.paymentId());
            return new PaymentProviderException("Refund already existed at the provider and was applied locally");
        }
        markFailed(prepared, reason);
        return new PaymentProviderException("Refund was rejected by the provider", cause);
    }

    private Optional<String> lookupSucceededRefund(String providerPaymentId) {
        JsonNode list;
        try {
            list = client.get().uri(uri -> uri.path("/refunds")
                            .queryParam("payment_id", providerPaymentId).queryParam("limit", 100).build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new PaymentProviderException("Refund lookup is temporarily unavailable", e);
        }
        if (list == null || !list.path("items").isArray())
            throw new PaymentProviderException("Refund lookup returned an unreadable response");
        for (JsonNode item : list.path("items")) {
            String id = succeededRefundId(item, providerPaymentId);
            if (id != null) return Optional.of(id);
        }
        return Optional.empty();
    }

    private static String succeededRefundId(JsonNode refund, String providerPaymentId) {
        if (refund == null) return null;
        String id = refund.path("id").asText();
        if (id.isBlank() || !"succeeded".equals(refund.path("status").asText())) return null;
        if (!providerPaymentId.equals(refund.path("payment_id").asText())) return null;
        return id;
    }

    private static boolean isRefundInFlight(Payment payment) {
        return payment.getRefundRequestedAt() != null
                && payment.getRefundedAt() == null
                && payment.getRefundFailedAt() == null;
    }

    private static Duration backoff(int attempts) {
        Duration delay = FIRST_CHECK_DELAY.multipliedBy(Math.min(1L << Math.min(attempts, 5), 32));
        return delay.compareTo(MAX_CHECK_DELAY) > 0 ? MAX_CHECK_DELAY : delay;
    }

    private static String idempotenceKey(UUID paymentId) {
        return "refund:" + paymentId;
    }

    private record Prepared(UUID orderId, UUID paymentId, String providerPaymentId, BigDecimal amount) {}

    public record RefundResult(UUID orderId, UUID paymentId, String providerRefundId, BigDecimal amount) {}
}
