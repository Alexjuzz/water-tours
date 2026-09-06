package ru.Water_Tours.ticket.model.payment;

import ru.Water_Tours.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentStartResponse(UUID paymentId, UUID orderId, PaymentStatus status, BigDecimal amount, String paymentUrl) {
}
