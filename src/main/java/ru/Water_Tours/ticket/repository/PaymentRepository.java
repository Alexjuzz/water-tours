package ru.Water_Tours.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.Water_Tours.ticket.model.payment.Payment;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    java.util.List<Payment> findAllByOrderId(UUID orderId);

    java.util.List<Payment> findTop50ByStatusAndNextCheckAtBeforeOrderByNextCheckAtAsc(
            ru.Water_Tours.enums.PaymentStatus status, java.time.Instant now);

    @org.springframework.data.jpa.repository.Query("select p.order.id from Payment p where p.id = :id")
    UUID findOrderId(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    // Refunds whose provider outcome is still unknown locally: requested, not yet recorded as
    // refunded and not yet recorded as failed.
    @org.springframework.data.jpa.repository.Query("""
            select p.id from Payment p
            where p.refundRequestedAt is not null
              and p.refundedAt is null
              and p.refundFailedAt is null
              and p.refundNextCheckAt is not null
              and p.refundNextCheckAt < :now
            order by p.refundNextCheckAt asc""")
    java.util.List<UUID> findRefundIdsAwaitingReconciliation(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
