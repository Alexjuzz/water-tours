package ru.Water_Tours.ticket.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Redis-backed store. Generic over the stored value through an explicit codec, so the value can
 * carry the request and caller bindings alongside the order id without this class knowing what
 * they mean.
 */
public class RedisIdempotencyStore<T> implements IdempotencyStore<T> {
    private final StringRedisTemplate redisTemplate;
    private final Function<T, String> encoder;
    private final Function<String, T> decoder;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate,
                                 Function<T, String> encoder,
                                 Function<String, T> decoder) {
        this.redisTemplate = redisTemplate;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    @Override
    public Optional<T> getValue(String scope, String key) {
        String value = redisTemplate.opsForValue().get(valueKey(scope, key));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(decoder.apply(value));
    }

    @Override
    public boolean tryLock(String scope, String key, Duration lockTtl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey(scope, key), "1", lockTtl));
    }

    @Override
    public void unlock(String scope, String key) {
        redisTemplate.delete(lockKey(scope, key));
    }

    @Override
    public void setValue(String scope, String key, T value, Duration valueTtl) {
        redisTemplate.opsForValue().set(valueKey(scope, key), encoder.apply(value), valueTtl);
    }

    //REGION private methods
    private String valueKey(String scope, String key) {
        return String.format("idem:%s:value:%s", scope.trim(), key.trim());
    }

    private String lockKey(String scope, String key) {
        return String.format("idem:%s:lock:%s", scope.trim(), key.trim());
    }
    //ENDREGION
}
