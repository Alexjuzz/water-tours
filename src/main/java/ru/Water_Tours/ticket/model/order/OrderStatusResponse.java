package ru.Water_Tours.ticket.model.order;

import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything the purchase screen needs to tell the customer where their order stands, in one
 * token-protected read. Without it the site has to infer "paid", "cancelled" and "still waiting"
 * from whether a ticket list happens to be empty, which reads a cancelled payment as a slow one.
 *
 * <p>{@code testPaid} is the one field here that exists for the frontend's analytics, not for the
 * customer: the client-side "payment confirmed" signal (see {@code water-tours-buy.js}) has no
 * other way to know whether an order was paid for real or through
 * {@code LocalCheckoutService}/{@code StaffTestOrderService}'s test-pay paths, both of which
 * already set {@link ru.Water_Tours.ticket.model.order.Order#getTestPaid()} to {@code true} for
 * exactly this reason (the automatic delivery queue already excludes them on the same flag). No
 * schema change was needed to add this - the column has existed since the test-order tooling was
 * built; it simply was not on this DTO before.
 */
public record OrderStatusResponse(
        UUID orderId,
        OrderStatus status,
        OrderType orderType,
        BigDecimal totalAmount,
        Instant paidAt,
        boolean ticketsIssued,
        int ticketCount,
        boolean pdfAvailable,
        boolean refundInProgress,
        Instant ticketsEmailedAt,
        boolean emailResendAvailable,
        String pdfUrl,
        boolean testPaid) {
}
