package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.enums.*;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.*;
import ru.Water_Tours.ticket.repository.*;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final String baseUrl;

    public PaymentService(OrderRepository orders, PaymentRepository payments, @org.springframework.beans.factory.annotation.Qualifier("yookassaRestClientBuilder") RestClient.Builder builder,
                          PlatformTransactionManager transactions, ObjectMapper mapper,
                          @Value("${yookassa.shopId}") String shopId,
                          @Value("${yookassa.secretKey}") String secretKey,
                          @Value("${app.base-url}") String baseUrl) {
        this.orders = orders;
        this.payments = payments;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        tx = new TransactionTemplate(transactions);
        // The attempt must survive a network failure or rollback in a caller.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        client = builder.clone()
                .baseUrl("https://api.yookassa.ru/v3")
                .defaultHeaders(h -> h.setBasicAuth(shopId, secretKey)).build();
    }

    public PaymentStartResponse startPayment(UUID orderId) {
        Attempt attempt = tx.execute(status -> {
            Order order = lockOrder(orderId);
            List<Payment> existing = payments.findAllByOrderId(orderId);
            if (existing.size() > 1) throw new IllegalStateException("Multiple payments require manual review");
            if (!existing.isEmpty()) return snapshot(existing.getFirst());
            if (order.getStatus() != OrderStatus.DRAFT)
                throw new IllegalStateException("Order must be in DRAFT to start payment");
            if (order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0)
                throw new IllegalStateException("Order amount must be positive");
            Payment payment = new Payment();
            payment.setOrder(order);
            payment.setProvider(PaymentProvider.YOOKASSA);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setAmount(order.getTotalAmount());
            payments.saveAndFlush(payment);
            try {
                payment.setRequestBody(mapper.writeValueAsString(Map.of(
                        "amount", Map.of("value", payment.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency", "RUB"),
                        // No backend page exists at a REST path; send the customer back to the
                        // WordPress purchase section, which restores order status from sessionStorage.
                        "confirmation", Map.of("type", "redirect", "return_url", baseUrl + "/#buy"),
                        "capture", true,
                        "description", "Оплата заказа Water Tours " + orderId,
                        "metadata", Map.of("orderId", orderId.toString(), "paymentId", payment.getId().toString()))));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare payment request", e);
            }
            payment.setNextCheckAt(Instant.now().plusSeconds(60));
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            orders.save(order);
            payments.save(payment);
            return snapshot(payment);
        });
        if (terminal(attempt.status()) || attempt.url() != null) return response(attempt);
        Attempt refreshed = refresh(attempt);
        if (!terminal(refreshed.status()) && refreshed.url() == null)
            throw new PaymentProviderException("Payment confirmation link is not available yet");
        return response(refreshed);
    }

    public PaymentStartResponse handleWebhook(WebhookRequestDTO request) {
        if (request == null || request.getObject() == null || request.getObject().getId() == null)
            return null;
        // Refund notifications carry a refund ID, not a payment ID.
        if (!Set.of("payment.succeeded", "payment.canceled", "payment.waiting_for_capture").contains(
                Objects.toString(request.getEvent(), ""))) return null;
        Attempt attempt = tx.execute(status -> payments.findByProviderPaymentId(request.getObject().getId())
                .map(this::snapshot).orElse(null));
        // An early webhook may arrive before create response is committed. Reconciliation recovers it.
        if (attempt == null) return null;
        return response(terminal(attempt.status()) ? attempt : refresh(attempt));
    }

    public void reconcilePendingPayments() {
        List<UUID> due = tx.execute(status -> payments
                .findTop50ByStatusAndNextCheckAtBeforeOrderByNextCheckAtAsc(PaymentStatus.PENDING, Instant.now())
                .stream().map(Payment::getId).toList());
        for (UUID id : due) {
            try {
                Attempt attempt = tx.execute(status -> {
                    lockOrder(payments.findOrderId(id));
                    Payment payment = payments.findById(id).orElseThrow();
                    payment.setNextCheckAt(Instant.now().plusSeconds(60));
                    payments.save(payment);
                    return snapshot(payment);
                });
                if (!terminal(attempt.status())) refresh(attempt);
            } catch (Exception e) {
                log.warn("Payment reconciliation failed for paymentId={}, errorType={}", id, e.getClass().getSimpleName());
            }
        }
    }

    private Attempt refresh(Attempt attempt) {
        JsonNode provider;
        if (attempt.providerId() == null) {
            // Stop before YooKassa's 24-hour deduplication window expires.
            if (attempt.requestBody() == null || attempt.createdAt().isBefore(Instant.now().minus(Duration.ofHours(23)))) {
                tx.executeWithoutResult(status -> {
                    lockOrder(attempt.orderId());
                    Payment p = payments.findById(attempt.id()).orElseThrow();
                    p.setNextCheckAt(null);
                    payments.save(p);
                });
                throw new PaymentProviderException("Payment requires manual reconciliation");
            }
            try {
                provider = client.post().uri("/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotence-Key", attempt.id().toString())
                        .body(attempt.requestBody()).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                throw new PaymentProviderException("Payment provider is temporarily unavailable", e);
            }
        } else {
            try {
                provider = client.get().uri("/payments/{id}", attempt.providerId()).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                throw new PaymentProviderException("Payment provider is temporarily unavailable", e);
            }
        }
        validate(attempt, provider);
        return tx.execute(status -> {
            Order order = lockOrder(attempt.orderId());
            Payment payment = payments.findById(attempt.id()).orElseThrow();
            String providerId = provider.path("id").asText();
            if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().equals(providerId))
                throw new PaymentProviderException("Provider payment identity mismatch");
            if (terminal(payment.getStatus())) return snapshot(payment);
            payment.setProviderPaymentId(providerId);
            String url = provider.path("confirmation").path("confirmation_url").asText(null);
            if (url != null) payment.setConfirmationUrl(url);
            switch (provider.path("status").asText()) {
                case "succeeded" -> {
                    payment.setStatus(PaymentStatus.SUCCEEDED);
                    payment.setSucceededAt(Instant.parse(provider.path("captured_at").asText()));
                    order.setStatus(OrderStatus.PAID);
                    order.setPaidAt(payment.getSucceededAt());
                    payment.setNextCheckAt(null);
                }
                case "canceled" -> {
                    payment.setStatus(PaymentStatus.CANCELED);
                    if (order.getStatus() != OrderStatus.PAID) order.setStatus(OrderStatus.CANCELLED);
                    payment.setNextCheckAt(null);
                }
                default -> payment.setNextCheckAt(Instant.now().plusSeconds(60));
            }
            payments.save(payment);
            if (terminal(payment.getStatus())) orders.save(order);
            return snapshot(payment);
        });
    }

    private void validate(Attempt attempt, JsonNode provider) {
        try {
            if (provider == null || provider.path("id").asText().isBlank()
                    || (attempt.providerId() != null && !attempt.providerId().equals(provider.path("id").asText()))
                    || !provider.path("amount").path("currency").asText().equals("RUB")
                    || new java.math.BigDecimal(provider.path("amount").path("value").asText()).compareTo(attempt.amount()) != 0
                    || !provider.path("metadata").path("orderId").asText().equals(attempt.orderId().toString())
                    || !provider.path("metadata").path("paymentId").asText().equals(attempt.id().toString()))
                throw new IllegalArgumentException("Payment identity or amount mismatch");
            String status = provider.path("status").asText();
            if (!Set.of("pending", "waiting_for_capture", "succeeded", "canceled").contains(status))
                throw new IllegalArgumentException("Unknown payment status");
            if (status.equals("succeeded")) {
                if (!provider.path("paid").asBoolean()) throw new IllegalArgumentException("Payment is not paid");
                Instant.parse(provider.path("captured_at").asText());
            }
        } catch (Exception e) {
            throw new PaymentProviderException("Provider response failed validation", e);
        }
    }

    private Order lockOrder(UUID id) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> new NoSuchElementException("Order not found"));
    }

    private boolean terminal(PaymentStatus status) {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.CANCELED;
    }

    private Attempt snapshot(Payment p) {
        return new Attempt(p.getId(), p.getOrder().getId(), p.getAmount(), p.getStatus(),
                p.getProviderPaymentId(), p.getConfirmationUrl(), p.getRequestBody(), p.getCreatedAt());
    }

    private PaymentStartResponse response(Attempt a) {
        return new PaymentStartResponse(a.id(), a.orderId(), a.status(), a.amount(), a.url());
    }

    private record Attempt(UUID id, UUID orderId, java.math.BigDecimal amount, PaymentStatus status,
                           String providerId, String url, String requestBody, Instant createdAt) {}
}
