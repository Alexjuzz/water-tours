package ru.Water_Tours.ticket.idempotency;

import ru.Water_Tours.exceptions.globalExceptionHandler.StillProcessingException;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public class IdempotencyService<T> {

    private final IdempotencyStore<T> idempotencyStore;

    private final Duration lockTtl;
    private final Duration waitTimeout;
    private final Duration pollInterval;
    private final Duration valueTtl;

    public IdempotencyService(IdempotencyStore<T> idempotencyStore, Duration valueTtl, Duration lockTtl, Duration waitTimeout, Duration pollInterval) {
        this.idempotencyStore = idempotencyStore;
        this.valueTtl = valueTtl;
        this.lockTtl = lockTtl;
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;
    }


    public ResolveResult<T> resolve(String scope, String key, Supplier<T> supplier) {
        validateArgs(scope,key);

        scope = scope.trim();
        key = key.trim();

        Optional<T> cached = idempotencyStore.getValue(scope, key);
        if (cached.isPresent()) {
            return new ResolveResult<>(cached.get(), true);
        }

        boolean locked = idempotencyStore.tryLock(scope, key, lockTtl);
        if (locked) {
            try {
                T result = supplier.get();
                idempotencyStore.setValue(scope, key, result, valueTtl);
                return new ResolveResult<>(result, false);
            } finally {
                idempotencyStore.unlock(scope, key);
            }
        }
        long deadLine = System.currentTimeMillis() + waitTimeout.toMillis();

        while (System.currentTimeMillis() < deadLine) {
            Optional<T> v = idempotencyStore.getValue(scope, key);
            if (v.isPresent()) {
                return new ResolveResult<>(v.get(), true);
            } else {
                sleep(pollInterval);
            }
        }
        throw new StillProcessingException("Ожидание вышло. Метод resolve - idempotencyService");
    }

    //REGION PRIVATE METHODS
    private void sleep(Duration durationTtl) {
        try {
            Thread.sleep(durationTtl.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StillProcessingException(e.toString());
        }
    }

    private void validateArgs(String scope, String key) {
        if(scope == null || scope.isBlank()){
            throw new IllegalArgumentException("Scope must NOT be blank");
        }
        if(key == null || key.isBlank()){
            throw new IllegalArgumentException("Key must NOT be blank");
        }
        if(valueTtl == null || valueTtl.isNegative() || valueTtl.isZero()){
            throw new IllegalArgumentException("valueTtl must be > 0");
        }
        if(lockTtl == null || lockTtl.isZero() || lockTtl.isNegative()){
            throw new IllegalArgumentException("lockTtl must be  > 0");
        }
        if(waitTimeout == null || waitTimeout.isNegative() || waitTimeout.isZero()){
            throw  new IllegalArgumentException("waitTimeout must be > 0");
        }
        if(pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()){
            throw new IllegalArgumentException("pollInterval must be > 0");
        }
    }

    //ENDREGION

}
