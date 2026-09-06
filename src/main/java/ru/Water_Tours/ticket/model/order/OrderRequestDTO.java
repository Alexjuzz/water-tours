package ru.Water_Tours.ticket.model.order;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import ru.Water_Tours.enums.TicketType;

import java.util.Map;

public record OrderRequestDTO(@NotBlank @Email String email,
                              String phoneNumber,
                              Map<TicketType, Integer> tickets) {
}