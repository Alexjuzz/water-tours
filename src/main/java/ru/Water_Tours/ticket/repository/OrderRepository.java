package ru.Water_Tours.ticket.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

    @Query("select o.id from Order o where o.status = ru.Water_Tours.enums.OrderStatus.PAID and o.ticketIssuedAt is null")
    List<UUID> findPaidOrderIdsAwaitingTickets();

    @Query("select o.id from Order o where o.status = ru.Water_Tours.enums.OrderStatus.PAID and o.ticketIssuedAt is not null and o.ticketsEmailedAt is null")
    List<UUID> findOrderIdsAwaitingTicketEmail();

    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    List<Order> findAllByTelegramChatIdOrderByCreatedAtDesc(Long telegramChatId);

    List<Order> findAllByEmailIgnoreCaseOrderByCreatedAtDesc(String email);
}
