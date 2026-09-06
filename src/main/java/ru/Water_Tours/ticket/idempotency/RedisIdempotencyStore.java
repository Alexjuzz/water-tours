package ru.Water_Tours.ticket.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class RedisIdempotencyStore implements IdempotencyStore<UUID> {
    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<UUID> getValue(String scope, String key) {

        String value = redisTemplate.opsForValue().get(valueKey(scope, key));

        if(value == null || value.isEmpty() || value.isBlank() ){
            return Optional.empty();
        }
       java.util.UUID uuid = java.util.UUID.fromString(value);
        return  Optional.of(uuid);
    }

    @Override
    public boolean tryLock(String scope, String key, Duration lockTtl) {

        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey(scope, key), "1", lockTtl));
    }

    @Override
    public void unlock(String scope, String key) {
        String lockKey = lockKey(scope, key);
        redisTemplate.delete(lockKey);

    }

    @Override
    public void setValue(String scope, String key, UUID value, Duration valueTtl) {

            String valueKey = valueKey(scope,key);
            redisTemplate.opsForValue().set(valueKey,value.toString(),valueTtl);
    }

    //REGION private methods
    private String valueKey(String scope, String key) {
        scope = scope.trim();
        key = key.trim();
        return String.format("idem:%s:value:%s", scope, key);
    }

    private String lockKey(String scope, String key) {
        scope = scope.trim();
        key = key.trim();
        return String.format("idem:%s:lock:%s", scope, key);
    }

    //ENDREGION
}
