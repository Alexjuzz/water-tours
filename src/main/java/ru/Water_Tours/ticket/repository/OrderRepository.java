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

    /**
     * Automatic delivery queue. Two exclusions on top of "paid, issued, not yet emailed":
     * orders paid before the configured start date stay held for explicit review (the backlog
     * accumulated while delivery was off contains addresses nobody has checked), and test orders
     * never join production delivery - staff send those explicitly.
     */
    @Query("select o.id from Order o where o.status = ru.Water_Tours.enums.OrderStatus.PAID "
            + "and o.ticketIssuedAt is not null and o.ticketsEmailedAt is null "
            + "and (o.testPaid is null or o.testPaid = false) and o.paidAt > :paidFrom")
    List<UUID> findOrderIdsAwaitingTicketEmailPaidAfter(@Param("paidFrom") Instant paidFrom);

    @Query("select o from Order o where o.status = ru.Water_Tours.enums.OrderStatus.PAID "
            + "and o.ticketIssuedAt is not null and o.ticketsEmailedAt is null order by o.paidAt desc")
    List<Order> findOrdersAwaitingTicketEmail();

    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    List<Order> findAllByTelegramChatIdOrderByCreatedAtDesc(Long telegramChatId);

    List<Order> findAllByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    List<Order> findAllByTestPaidIsTrueOrderByCreatedAtDesc();
}
