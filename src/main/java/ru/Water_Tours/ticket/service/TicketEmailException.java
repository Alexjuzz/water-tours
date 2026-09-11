package ru.Water_Tours.ticket.service;

/** The mail server could not be reached or refused the message; the attempt may be retried. */
public class TicketEmailException extends RuntimeException {
    public TicketEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
