package ru.Water_Tours.ticket.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class IdempotencyConfig {


    @Bean
    @Profile("!redis")
    public IdempotencyStore<UUID> idempotencyInMemoryStore() {
        return new InMemoryIdempotencyStore<>();
    }

    @Bean
    @Profile("redis")
    public IdempotencyStore<UUID> idempotencyRedisStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate);
    }

    @Bean
    public IdempotencyService<UUID> idempotencyService(IdempotencyStore<UUID> store,
                                                       @Value("${idempotency.value-ttl:10m}") Duration valueTtl,
                                                       @Value("${idempotency.lock-ttl:45s}") Duration lockTtl,
                                                       @Value("${idempotency.wait-timeout:3s}") Duration waitTimeout,
                                                       @Value("${idempotency.poll-interval:100ms}") Duration pollInterval) {

        return new IdempotencyService<>(store, valueTtl, lockTtl, waitTimeout, pollInterval);
    }
}
