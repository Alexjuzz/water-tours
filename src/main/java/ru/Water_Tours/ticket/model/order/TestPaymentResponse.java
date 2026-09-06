package ru.Water_Tours.ticket.model.order;

import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestPaymentResponse(
        UUID id,
        OrderStatus status,
        boolean testPaid,
        Instant paidAt,
        List<TicketResponse> tickets,
        String pdfUrl
) {
}
