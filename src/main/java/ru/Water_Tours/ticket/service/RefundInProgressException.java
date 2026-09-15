package ru.Water_Tours.ticket.service;

/**
 * A ticket cannot be redeemed because its order has a refund being processed right now.
 *
 * <p>It extends {@link IllegalStateException} so every existing caller keeps behaving exactly as
 * before; what it adds is the ability to say <em>why</em> without reading the message text. That
 * matters at the gate: "this ticket is on hold while the money goes back" and "this ticket has
 * expired" lead to completely different conversations with the customer, and telling a staff
 * member the wrong one sends them into the wrong one.
 */
public class RefundInProgressException extends IllegalStateException {
    public RefundInProgressException(String message) {
        super(message);
    }
}
