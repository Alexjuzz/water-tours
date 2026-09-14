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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

    /**
     * Resolves an idempotency key that is no longer in the cache. Without it a repeat of the same
     * key after the cache TTL either created a second order or hit the unique constraint and
     * surfaced as a 500.
     */
    Optional<Order> findByIdempotencyCode(String idempotencyCode);

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

    /**
     * Exact lookup by phone for the staff support console. Stored numbers keep the shape the
     * customer typed, so the comparison normalises the column to digits and matches it against
     * the equivalent spellings of the searched number (see {@code PhoneNormalizer}). Equality
     * only - there is no prefix or partial match, so this cannot be used to enumerate customers.
     *
     * Orders with no phone reduce to an empty digit string, which would make an empty search term
     * match all of them at once. They are excluded here rather than only at the caller, so the
     * guarantee holds for whoever calls this next.
     */
    @Query(value = "select * from orders o "
            + "where regexp_replace(coalesce(o.phone, ''), '[^0-9]', '', 'g') <> '' "
            + "and regexp_replace(coalesce(o.phone, ''), '[^0-9]', '', 'g') in (:digits) "
            + "order by o.created_at desc", nativeQuery = true)
    List<Order> findAllByNormalizedPhone(@Param("digits") Collection<String> digits);
}
