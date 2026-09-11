package ru.Water_Tours.ticket.model.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.Water_Tours.enums.PaymentProvider;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.ticket.model.order.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    @Column(name = "payment_status",nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    @Column(name = "payment_provider",nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentProvider provider;
    @Column(name = "provider_payment_id")
    private String providerPaymentId;
    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "request_body", columnDefinition = "text")
    private String requestBody;
    @Column(name = "confirmation_url", columnDefinition = "text")
    private String confirmationUrl;
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "provider_refund_id")
    private String providerRefundId;
    @Column(name = "refunded_amount", precision = 12, scale = 2)
    private BigDecimal refundedAmount;
    @Column(name = "refunded_at")
    private Instant refundedAt;

    // Refund recovery state. A refund is "in flight" while refundRequestedAt is set and neither
    // refundedAt nor refundFailedAt is: that marker is written before the provider is called, so a
    // crash or a failed local commit after a successful provider refund is still recoverable.
    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;
    @Column(name = "refund_failed_at")
    private Instant refundFailedAt;
    @Column(name = "refund_failure_reason", length = 300)
    private String refundFailureReason;
    @Column(name = "refund_next_check_at")
    private Instant refundNextCheckAt;
    @Column(name = "refund_check_attempts")
    private Integer refundCheckAttempts;

    @PrePersist
    void  prePersist(){
        if(status == null) status = PaymentStatus.NEW;
        if(provider == null) provider = PaymentProvider.YOOKASSA;
        if(createdAt == null) createdAt = Instant.now();
    }

}
