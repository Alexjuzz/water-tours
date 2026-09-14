package ru.Water_Tours.ticket.idempotency;

import java.util.UUID;

/**
 * What an {@code Idempotency-Key} resolves to.
 *
 * <p>The key alone used to be the whole record, which made the key behave as a bearer credential:
 * replaying somebody else's key returned their order, including its access token. It is not a
 * credential - it is a request header, readable by any script on the page and by anything that
 * logs headers - so two extra bindings travel with the order id:
 *
 * <ul>
 *   <li>{@code requestHash} - a fingerprint of the order that was actually created. A replay that
 *       carries a different payload is a different request wearing the same key, and is refused
 *       rather than answered with the stored order.</li>
 *   <li>{@code callerHash} - a fingerprint of the caller's own {@code Idempotency-Secret}. This,
 *       not the request hash, is what proves ownership: a body fingerprint can be reproduced by
 *       anyone who knows what the victim bought. {@code null} means the record was created by a
 *       caller that sent no secret (an older storefront build); such a record can still be
 *       replayed, but the reply carries no token, e-mail or phone.</li>
 * </ul>
 */
public record IdempotencyRecord(UUID orderId, String requestHash, String callerHash) {

    private static final String ABSENT = "-";

    public String encode() {
        return orderId + "|" + value(requestHash) + "|" + value(callerHash);
    }

    public static IdempotencyRecord decode(String stored) {
        String[] parts = stored.split("\\|", -1);
        if (parts.length != 3) {
            // A value written by an older build held the bare order id. Treat it as a record with
            // no bindings: it is still resolvable, and the reply is redacted for want of a caller.
            return new IdempotencyRecord(UUID.fromString(stored), null, null);
        }
        return new IdempotencyRecord(UUID.fromString(parts[0]), parse(parts[1]), parse(parts[2]));
    }

    private static String value(String field) {
        return field == null || field.isBlank() ? ABSENT : field;
    }

    private static String parse(String field) {
        return ABSENT.equals(field) || field.isBlank() ? null : field;
    }
}
