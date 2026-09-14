package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.idempotency.IdempotencyConflictException;
import ru.Water_Tours.ticket.idempotency.IdempotencyRecord;
import ru.Water_Tours.ticket.idempotency.IdempotencyService;
import ru.Water_Tours.ticket.idempotency.InMemoryIdempotencyStore;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H-1. An {@code Idempotency-Key} used to be the only thing standing between a stranger and
 * somebody else's order: replaying one returned the original order's access token, e-mail and
 * phone. These tests pin the four cases that decide whether that is still true.
 */
class OrderCreationServiceTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private static final String OWNER_SECRET = "owner-secret-value";
    private static final String OTHER_SECRET = "someone-elses-secret";

    /** Stands in for the orders table: an idempotency code maps to at most one order. */
    private final Map<String, Order> stored = new ConcurrentHashMap<>();
    private final AtomicInteger created = new AtomicInteger();
    private final OrderService orderService = mock(OrderService.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);

    @org.junit.jupiter.api.BeforeEach
    void stubTheOrdersTable() {
        when(orderRepository.findByIdempotencyCode(anyString()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(orderService.createOrder(any(), anyString(), any(), any())).thenAnswer(call -> {
            created.incrementAndGet();
            Order order = new Order();
            order.setId(UUID.randomUUID());
            order.setIdempotencyCode(call.getArgument(1));
            order.setIdempotencyRequestHash(call.getArgument(2));
            order.setIdempotencyCallerHash(call.getArgument(3));
            stored.put(order.getIdempotencyCode(), order);
            return order;
        });
    }

    /** A new service over an empty cache - the same orders table, as after the 10-minute TTL. */
    private OrderCreationService serviceWithFreshCache() {
        return serviceOver(new InMemoryIdempotencyStore<>());
    }

    private OrderCreationService serviceOver(InMemoryIdempotencyStore<IdempotencyRecord> store) {
        IdempotencyService<IdempotencyRecord> idempotency = new IdempotencyService<>(
                store, Duration.ofMinutes(10), Duration.ofSeconds(45),
                Duration.ofSeconds(3), Duration.ofMillis(20));
        return new OrderCreationService(orderService, orderRepository, idempotency);
    }

    /** An order row as it exists before the binding columns were filled in: both hashes null. */
    private Order legacyOrderRow() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setIdempotencyCode(KEY);
        stored.put(KEY, order);
        return order;
    }

    private static OrderRequestDTO twoAdultTickets() {
        return new OrderRequestDTO("buyer@example.com", "+7 900 000-00-00", Map.of(TicketType.ADULT, 2));
    }

    private static OrderRequestDTO oneAdultTicketForSomebodyElse() {
        return new OrderRequestDTO("attacker@example.com", null, Map.of(TicketType.ADULT, 1));
    }

    @Test
    void theSameCallerRetryingGetsTheSameOrderAndMayStillSeeItsCredentials() {
        OrderCreationService service = serviceWithFreshCache();

        OrderCreationService.Result first = service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);
        OrderCreationService.Result retry = service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThat(first.reused()).isFalse();
        assertThat(retry.reused()).isTrue();
        assertThat(retry.orderId()).isEqualTo(first.orderId());
        // This is the recovery case: a first response that never arrived must still be obtainable,
        // or the customer pays twice.
        assertThat(retry.callerAuthorized()).isTrue();
        assertThat(created).hasValue(1);
    }

    @Test
    void aDifferentCallerReplayingTheKeyIsRefusedEvenWithTheIdenticalBody() {
        OrderCreationService service = serviceWithFreshCache();
        service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThatThrownBy(() -> service.createOrReuse(twoAdultTickets(), KEY, OTHER_SECRET))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(created).hasValue(1);
    }

    @Test
    void replayingTheKeyWithNoSecretAtAllIsRefusedWhenTheRecordHasOne() {
        OrderCreationService service = serviceWithFreshCache();
        service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThatThrownBy(() -> service.createOrReuse(twoAdultTickets(), KEY, null))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void replayingTheKeyWithADifferentPayloadIsRefusedAndCreatesNoSecondOrder() {
        OrderCreationService service = serviceWithFreshCache();
        service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThatThrownBy(() -> service.createOrReuse(oneAdultTicketForSomebodyElse(), KEY, OWNER_SECRET))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(created).hasValue(1);
    }

    /**
     * Transition path: a record created by a storefront build that sends no secret. The retry is
     * still honoured - the order is real and the payload matches - but nothing proves who is
     * asking, so the caller is not authorised to see the order's credentials.
     */
    @Test
    void aRecordCreatedWithoutASecretIsReplayableButNeverAuthorised() {
        OrderCreationService service = serviceWithFreshCache();

        OrderCreationService.Result first = service.createOrReuse(twoAdultTickets(), KEY, null);
        OrderCreationService.Result retry = service.createOrReuse(twoAdultTickets(), KEY, null);
        OrderCreationService.Result byStranger = service.createOrReuse(twoAdultTickets(), KEY, OTHER_SECRET);

        assertThat(first.callerAuthorized()).isTrue();
        assertThat(retry.orderId()).isEqualTo(first.orderId());
        assertThat(retry.callerAuthorized()).isFalse();
        assertThat(byStranger.callerAuthorized()).isFalse();
        assertThat(created).hasValue(1);
    }

    /**
     * The cache entry lives ten minutes; the key lives on the order row for ever. A late retry
     * must still resolve to the same order - before this, it either created a second one or hit
     * the idempotency_code unique constraint and surfaced as a 500.
     */
    @Test
    void aRetryAfterTheCacheEntryExpiredStillResolvesToTheSameOrderUnderTheSameRules() {
        OrderCreationService before = serviceWithFreshCache();
        OrderCreationService.Result first = before.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        OrderCreationService afterExpiry = serviceWithFreshCache();

        OrderCreationService.Result retry = afterExpiry.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);
        assertThat(retry.orderId()).isEqualTo(first.orderId());
        assertThat(retry.reused()).isTrue();
        assertThat(retry.callerAuthorized()).isTrue();

        assertThatThrownBy(() -> afterExpiry.createOrReuse(twoAdultTickets(), KEY, OTHER_SECRET))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(created).hasValue(1);
    }

    @Test
    void twoConcurrentRequestsWithOneKeyCreateOneOrder() throws Exception {
        OrderCreationService service = serviceWithFreshCache();
        CyclicBarrier start = new CyclicBarrier(2);
        java.util.List<UUID> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable call = () -> {
            try {
                start.await();
                results.add(service.createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET).orderId());
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Thread one = new Thread(call);
        Thread two = new Thread(call);
        one.start();
        two.start();
        one.join();
        two.join();

        assertThat(failure.get()).isNull();
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(created).hasValue(1);
    }

    // ---------------------------------------------------------------- legacy records

    /**
     * The upgrade case that used to be broken. A cached value written by the old build is a bare
     * order id, so the decoded record carries no request hash - and the request-hash comparison
     * ran first and refused everything, which is the opposite of the documented behaviour. A
     * customer retrying across the upgrade would have been told the order could not be created,
     * and the honest reading of that message is "press it again".
     */
    @Test
    void aBareOrderIdLeftInTheCacheByTheOldBuildIsReplayedRedactedRatherThanRefused() {
        InMemoryIdempotencyStore<IdempotencyRecord> store = new InMemoryIdempotencyStore<>();
        Order legacy = legacyOrderRow();
        store.setValue(OrderCreationService.SCOPE, KEY,
                IdempotencyRecord.decode(legacy.getId().toString()), Duration.ofMinutes(10));

        OrderCreationService.Result retry =
                serviceOver(store).createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThat(retry.orderId()).isEqualTo(legacy.getId());
        assertThat(retry.reused()).isTrue();
        assertThat(retry.callerAuthorized()).isFalse();
        assertThat(created).hasValue(0);
    }

    /** The same record, reached through the orders table after the cache entry has gone. */
    @Test
    void aPreMigrationOrderRowIsReplayedRedactedRatherThanRefused() {
        Order legacy = legacyOrderRow();

        OrderCreationService.Result retry =
                serviceWithFreshCache().createOrReuse(twoAdultTickets(), KEY, OWNER_SECRET);

        assertThat(retry.orderId()).isEqualTo(legacy.getId());
        assertThat(retry.reused()).isTrue();
        assertThat(retry.callerAuthorized()).isFalse();
        assertThat(created).hasValue(0);
    }

    /**
     * A legacy record must not be claimable. Nothing about it can be verified, so no caller -
     * however insistent, and whatever payload it brings - is ever authorised, and the record is
     * never upgraded with a secret the caller supplied.
     */
    @Test
    void noCallerCanClaimALegacyRecordAndNoSecondOrderIsCreated() {
        Order legacy = legacyOrderRow();
        OrderCreationService service = serviceWithFreshCache();

        assertThat(service.createOrReuse(twoAdultTickets(), KEY, null).callerAuthorized()).isFalse();
        assertThat(service.createOrReuse(twoAdultTickets(), KEY, OTHER_SECRET).callerAuthorized()).isFalse();
        assertThat(service.createOrReuse(oneAdultTicketForSomebodyElse(), KEY, OTHER_SECRET)
                .callerAuthorized()).isFalse();

        assertThat(stored.get(KEY).getIdempotencyCallerHash()).isNull();
        assertThat(stored.get(KEY).getIdempotencyRequestHash()).isNull();
        assertThat(stored.get(KEY).getId()).isEqualTo(legacy.getId());
        assertThat(created).hasValue(0);
    }

    /** The codec half of the same story, pinned on its own. */
    @Test
    void decodingAnOldBareUuidValueYieldsARecordWithNoBindings() {
        UUID orderId = UUID.randomUUID();

        IdempotencyRecord decoded = IdempotencyRecord.decode(orderId.toString());

        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.requestHash()).isNull();
        assertThat(decoded.callerHash()).isNull();
    }
}
