package ru.Water_Tours.ticket.service;

import org.springframework.stereotype.Service;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.idempotency.Fingerprints;
import ru.Water_Tours.ticket.idempotency.IdempotencyConflictException;
import ru.Water_Tours.ticket.idempotency.IdempotencyRecord;
import ru.Water_Tours.ticket.idempotency.IdempotencyService;
import ru.Water_Tours.ticket.idempotency.ResolveResult;
import ru.Water_Tours.ticket.model.order.BoatRentalRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Creates an order for {@code POST /api/v1/orders}, and decides what a replayed
 * {@code Idempotency-Key} is allowed to see.
 *
 * <p>The rules, in the order they are applied to a replay:
 * <ol>
 *   <li><b>No binding at all on the stored record.</b> Honoured as a reuse with a redacted body.
 *       This is the legacy case - a bare order id left in Redis by an older build, or an order
 *       row written before the binding columns existed. Neither the payload nor the caller can be
 *       checked, so nothing is disclosed; refusing instead would turn every in-flight retry at
 *       upgrade time into an apparent failure, which is how duplicate payments happen.</li>
 *   <li><b>Different payload, same key.</b> Refused. The key stands for one order; answering with
 *       a stored order that does not match what was just asked for is how a stranger's key turned
 *       into a stranger's order.</li>
 *   <li><b>Payload matches, caller cannot prove ownership.</b> Refused when the record was created
 *       with an {@code Idempotency-Secret}; answered with a redacted body (order id and status
 *       only, no access token, e-mail or phone) when it was not. The redacted branch exists only
 *       for records created by a storefront build that predates the secret.</li>
 *   <li><b>Payload matches and the secret matches.</b> Full response, access token included. This
 *       is the case that matters operationally: a customer whose first response was lost presses
 *       the button again and recovers their order instead of paying twice.</li>
 * </ol>
 *
 * <p>Note what this does <em>not</em> claim. Hashing the body is not an ownership check on its own
 * - an attacker who knows what a victim bought can reproduce the body. The secret is the
 * ownership check; the body hash stops a key from being repointed at a different order.
 *
 * <p>The bindings are also written to the order row, so they outlive the cache entry: a repeat of
 * the same key after the TTL expires resolves to the same order under the same rules instead of
 * either creating a second one or failing on the unique constraint.
 */
@Service
public class OrderCreationService {

    public static final String SCOPE = "orders:create";

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final IdempotencyService<IdempotencyRecord> idempotencyService;

    public OrderCreationService(OrderService orderService,
                                OrderRepository orderRepository,
                                IdempotencyService<IdempotencyRecord> idempotencyService) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.idempotencyService = idempotencyService;
    }

    public Result createOrReuse(OrderRequestDTO request, String idempotencyKey, String callerSecret) {
        String requestHash = Fingerprints.sha256Hex(fingerprintOf(request));
        String callerHash = callerSecret == null || callerSecret.isBlank()
                ? null
                : Fingerprints.sha256Hex(callerSecret.trim());

        AtomicBoolean created = new AtomicBoolean(false);
        Supplier<IdempotencyRecord> supplier = () -> {
            Optional<Order> alreadyStored = orderRepository.findByIdempotencyCode(idempotencyKey);
            if (alreadyStored.isPresent()) {
                return recordOf(alreadyStored.get());
            }
            created.set(true);
            return recordOf(orderService.createOrder(request, idempotencyKey, requestHash, callerHash));
        };

        ResolveResult<IdempotencyRecord> resolved = idempotencyService.resolve(SCOPE, idempotencyKey, supplier);
        IdempotencyRecord record = resolved.value();
        if (created.get()) {
            return new Result(record.orderId(), false, true);
        }

        if (record.requestHash() == null) {
            // A record that predates request binding: a bare-UUID value left in Redis by an older
            // build, or an order row created before the binding columns existed. Nothing here can
            // verify either the payload or the caller, and the caller's own secret must NOT be
            // adopted - writing it now would let whoever replays the key claim the order. So the
            // retry is honoured to the extent that is safe: the order is confirmed, no second
            // order is created, and the reply carries no credential.
            return new Result(record.orderId(), true, false);
        }
        if (!Fingerprints.matches(record.requestHash(), requestHash)) {
            throw new IdempotencyConflictException(
                    "This Idempotency-Key already stands for a different order request.");
        }
        if (record.callerHash() == null) {
            // Created before the caller secret existed. The order is real and the payload matches,
            // so the retry is honoured - but nothing here proves who is asking, so the reply holds
            // no credential.
            return new Result(record.orderId(), true, false);
        }
        if (!Fingerprints.matches(record.callerHash(), callerHash)) {
            throw new IdempotencyConflictException(
                    "This Idempotency-Key already stands for a different order request.");
        }
        return new Result(record.orderId(), true, true);
    }

    //REGION PRIVATE METHODS

    private IdempotencyRecord recordOf(Order order) {
        return new IdempotencyRecord(order.getId(), order.getIdempotencyRequestHash(),
                order.getIdempotencyCallerHash());
    }

    /**
     * Canonical form of the order being asked for. Built from the parsed request rather than the
     * raw bytes so that key order, whitespace and an explicit {@code "CHILD": 0} do not read as a
     * different order and turn an honest retry into a conflict.
     */
    static String fingerprintOf(OrderRequestDTO request) {
        StringBuilder sb = new StringBuilder();
        sb.append("email=").append(normalise(request.email())).append('\n');
        sb.append("phone=").append(normalise(request.phoneNumber())).append('\n');
        sb.append("tickets=");
        Map<TicketType, Integer> tickets = request.tickets();
        if (tickets != null) {
            Map<TicketType, Integer> sorted = new TreeMap<>(tickets);
            for (Map.Entry<TicketType, Integer> entry : sorted.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                sb.append(entry.getKey().name()).append(':').append(entry.getValue()).append(';');
            }
        }
        sb.append('\n').append("boat=");
        BoatRentalRequestDTO boat = request.boatRental();
        if (boat != null) {
            sb.append(boat.durationMinutes()).append(':')
                    .append(boat.guestCount()).append(':')
                    .append(boat.routeType()).append(':')
                    .append(normalise(boat.routeNote()));
        }
        return sb.toString();
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    //ENDREGION

    /**
     * @param callerAuthorized whether the caller may be shown the order's credentials. False only
     *                         on a replay of a record that carries no caller binding.
     */
    public record Result(UUID orderId, boolean reused, boolean callerAuthorized) {
    }
}
