package ru.Water_Tours.ticket.idempotency;

/**
 * An {@code Idempotency-Key} was replayed by a caller that cannot show it owns the stored order,
 * or with a payload that differs from the one the key already stands for.
 *
 * <p>Both cases answer with the same status and the same message on purpose: telling the caller
 * which check failed would say whether the key exists at all.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
