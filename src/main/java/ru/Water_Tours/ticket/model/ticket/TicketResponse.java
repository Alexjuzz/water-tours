package ru.Water_Tours.ticket.model.ticket;

import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String code,
        String purchaseEmail,
        Instant validFrom,
        Instant validTo,
        Instant purchasedAt,
        TicketType ticketType,
        TicketStatus ticketStatus
) {

}