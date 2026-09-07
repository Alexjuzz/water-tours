package ru.Water_Tours.ticket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

// Staff-triggered only: a customer never calls this directly. Blocks refunding an order
// with an already-redeemed ticket, since money-back-but-still-boardable is the exact gap
// this service exists to close.
@Service
public class RefundService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final TicketRepository tickets;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public RefundService(OrderRepository orders, PaymentRepository payments, TicketRepository tickets,
                         @Qualifier("yookassaRestClientBuilder") RestClient.Builder builder,
                         PlatformTransactionManager transactions, ObjectMapper mapper,
                         @Value("${yookassa.shopId}") String shopId,
                         @Value("${yookassa.secretKey}") String secretKey) {
        this.orders = orders;
        this.payments = payments;
        this.tickets = tickets;
        this.mapper = mapper;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        client = builder.clone()
                .baseUrl("https://api.yookassa.ru/v3")
                .defaultHeaders(h -> h.setBasicAuth(shopId, secretKey)).build();
    }

    public RefundResult refund(UUID orderId) {
        Prepared prepared = tx.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new NoSuchElementException("Order not found"));
            if (order.getStatus() != OrderStatus.PAID)
                throw new IllegalStateException("Only a PAID order can be refunded");
            Payment payment = payments.findAllByOrderId(orderId).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No succeeded payment found for this order"));
            boolean anyUsed = tickets.findAllByOrderId(orderId).stream()
                    .anyMatch(t -> t.getTicketStatus() == TicketStatus.USED);
            if (anyUsed)
                throw new IllegalStateException("Cannot refund: at least one ticket has already been used");
            return new Prepared(payment.getId(), payment.getProviderPaymentId(), payment.getAmount());
        });

        JsonNode provider;
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "payment_id", prepared.providerPaymentId(),
                    "amount", Map.of("value", prepared.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "currency", "RUB")));
            provider = client.post().uri("/refunds").contentType(MediaType.APPLICATION_JSON)
                    // Stable per-payment key: a retry after a crash reuses the same refund
                    // instead of creating a second one within YooKassa's 24h dedup window.
                    .header("Idempotence-Key", "refund:" + prepared.paymentId())
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new PaymentProviderException("Refund request failed", e);
        }

        if (provider == null || provider.path("id").asText().isBlank()
                || !"succeeded".equals(provider.path("status").asText())
                || !prepared.providerPaymentId().equals(provider.path("payment_id").asText()))
            throw new PaymentProviderException("Refund was not confirmed as succeeded by provider");

        String refundId = provider.path("id").asText();
        return tx.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow();
            Payment payment = payments.findById(prepared.paymentId()).orElseThrow();
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setProviderRefundId(refundId);
            payment.setRefundedAmount(prepared.amount());
            payment.setRefundedAt(Instant.now());
            payments.save(payment);
            order.setStatus(OrderStatus.REFUNDED);
            orders.save(order);
            List<Ticket> ticketList = tickets.findAllByOrderId(orderId);
            for (Ticket t : ticketList) {
                if (t.getTicketStatus() == TicketStatus.ISSUED) t.setTicketStatus(TicketStatus.REVOKED);
            }
            tickets.saveAll(ticketList);
            return new RefundResult(order.getId(), payment.getId(), refundId, prepared.amount());
        });
    }

    private record Prepared(UUID paymentId, String providerPaymentId, BigDecimal amount) {}

    public record RefundResult(UUID orderId, UUID paymentId, String providerRefundId, BigDecimal amount) {}
}
