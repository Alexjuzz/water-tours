package ru.Water_Tours.ticket.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;




public class InMemoryIdempotencyStore<T> implements IdempotencyStore<T> {

    private static class ValueEntry<T> {
        final T value;
        final long expiresAtMillis;

        ValueEntry(T value, long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
            this.value = value;
        }
    }

    private final ConcurrentMap<String, ValueEntry<T>> values = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> locks = new ConcurrentHashMap<>();


    @Override
    public Optional<T> getValue(String scope, String key) {
        String k = fullKey(scope, key);
        long now = System.currentTimeMillis();

        ValueEntry<T> entry = values.get(k);
        if (entry == null) return Optional.empty();

        if (entry.expiresAtMillis <= now) {
            values.remove(k, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }


    @Override
    public boolean tryLock(String scope, String key, Duration lockTtl) {
        String k = fullKey(scope, key);
        long now = System.currentTimeMillis();
        long newExpiresAt = now + lockTtl.toMillis();

        Long prev = locks.putIfAbsent(k, newExpiresAt);
        if (prev == null) {
            return true;
        }
        if (prev <= now) {
            boolean replace = locks.replace(k, prev, newExpiresAt);
            return replace;
        }

        return false;

    }

    @Override
    public void unlock(String scope, String key) {
        locks.remove(fullKey(scope, key));
    }


    @Override
    public void setValue(String scope, String key, T value, Duration ttl) {
        String k = fullKey(scope, key);
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        values.put(k, new ValueEntry<>(value, expireAt));

    }

    // REGION
    private String fullKey(String scope, String key) {
        String s = scope.trim();
        String k = key.trim();
        return s + ":" + k;
    }
    // ENDREGION
}
