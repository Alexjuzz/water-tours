package ru.Water_Tours.ticket.model.ticket;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TicketRequestDto( @NotBlank @Email String purchaserEmail,
                                                 String code) {}

