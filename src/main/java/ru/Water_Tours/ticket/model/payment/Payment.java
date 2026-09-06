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

    @PrePersist
    void  prePersist(){
        if(status == null) status = PaymentStatus.NEW;
        if(provider == null) provider = PaymentProvider.YOOKASSA;
        if(createdAt == null) createdAt = Instant.now();
    }

}
