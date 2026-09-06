package ru.Water_Tours.ticket.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore<T> {
    Optional<T> getValue(String scope, String key);

    boolean tryLock(String scope, String key, Duration lockTtl);

    void unlock(String scope, String key);

    void setValue(String scope, String key, T value, Duration valueTtl);

}
