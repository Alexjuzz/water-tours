package ru.Water_Tours;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "yookassa.shopId=test-shop", "yookassa.secretKey=test-secret",
        "spring.mail.username=test@example.invalid", "spring.mail.password=test-password",
        "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
        "staff.username=test-staff", "staff.password=test-only-password",
        "staff.remember-me-key=test-only-remember-me-key"
})
@ActiveProfiles("redis")
@Testcontainers
class WaterToursApplicationTests {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void applicationConnectsToIsolatedPostgresAndRedis() {
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
        redisTemplate.opsForValue().set("startup-test", "ready");
        assertThat(redisTemplate.opsForValue().get("startup-test")).isEqualTo("ready");
        redisTemplate.delete("startup-test");
    }
}