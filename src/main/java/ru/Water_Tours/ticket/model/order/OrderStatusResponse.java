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
        String pdfUrl) {
}
