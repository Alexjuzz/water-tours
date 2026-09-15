package ru.Water_Tours.ticket.service;

/**
 * A ticket cannot be redeemed because its order has already been refunded.
 *
 * <p>Extends {@link IllegalArgumentException} for the same backwards-compatibility reason as
 * {@link RefundInProgressException}. Before this existed, a refunded order's ticket was reported
 * as "already used", which is both wrong and the kind of wrong that starts an argument at the
 * boarding point.
 */
public class OrderRefundedException extends IllegalArgumentException {
    public OrderRefundedException(String message) {
        super(message);
    }
}
