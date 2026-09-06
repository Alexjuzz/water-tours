package ru.Water_Tours.ticket.model.order;

import ru.Water_Tours.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String idempotencyKey,
        String email,
        Instant createdAt,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant paidAt,
        String phone,
        UUID accessToken
) {


}
