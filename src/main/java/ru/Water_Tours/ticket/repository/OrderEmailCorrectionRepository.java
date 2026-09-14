package ru.Water_Tours.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.Water_Tours.ticket.model.order.OrderEmailCorrection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderEmailCorrectionRepository extends JpaRepository<OrderEmailCorrection, UUID> {

    List<OrderEmailCorrection> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /** Drives the per-order cap: every attempt counts, successful or not. */
    long countByOrderId(UUID orderId);

    /** Drives the staff cooldown between two corrections of the same order. */
    Optional<OrderEmailCorrection> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
