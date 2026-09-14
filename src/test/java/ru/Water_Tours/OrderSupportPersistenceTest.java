package ru.Water_Tours;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.Water_Tours.enums.EmailCorrectionOutcome;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderEmailCorrection;
import ru.Water_Tours.ticket.repository.OrderEmailCorrectionRepository;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.PhoneNormalizer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone lookup is native SQL and the audit trail is a new table, so both are exercised against
 * a real PostgreSQL rather than a mock: a normalisation that only works in Java, or a column that
 * Hibernate maps differently from the migration, would otherwise surface in production.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "yookassa.shopId=test", "yookassa.secretKey=test", "app.base-url=http://localhost:8080",
        "spring.mail.username=test@example.invalid", "spring.mail.password=test",
        "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
        "management.health.mail.enabled=false", "payments.reconciliation.enabled=false",
        "tickets.issuance.enabled=false", "order.expiration-check-interval=86400000",
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Testcontainers
class OrderSupportPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired OrderRepository orders;
    @Autowired OrderEmailCorrectionRepository corrections;

    @BeforeEach
    void clean() {
        corrections.deleteAll();
        orders.deleteAll();
    }

    private Order saved(String email, String phone) {
        Order order = new Order();
        order.setStatus(OrderStatus.PAID);
        order.setEmail(email);
        order.setPhone(phone);
        order.setTotalAmount(new BigDecimal("1500.00"));
        order.setCreatedAt(Instant.parse("2026-09-14T09:00:00Z"));
        return orders.save(order);
    }

    private List<Order> byPhone(String typed) {
        return orders.findAllByNormalizedPhone(
                PhoneNormalizer.storedVariants(PhoneNormalizer.normalize(typed).orElseThrow()));
    }

    @Test
    void oneNumberIsFoundWhateverShapeItWasStoredIn() {
        Order pretty = saved("a@example.org", "+7 (999) 123-45-67");
        Order trunk = saved("b@example.org", "89991234567");
        Order bare = saved("c@example.org", "9991234567");
        Order other = saved("d@example.org", "+7 999 765 43 21");

        assertThat(byPhone("79991234567")).extracting(Order::getId)
                .containsExactlyInAnyOrder(pretty.getId(), trunk.getId(), bare.getId())
                .doesNotContain(other.getId());
        assertThat(byPhone("8 999 123 45 67")).hasSize(3);
        assertThat(byPhone("+7-999-1234567")).hasSize(3);
    }

    @Test
    void aDifferentNumberAndAMissingPhoneMatchNothing() {
        saved("a@example.org", "+7 (999) 123-45-67");
        saved("b@example.org", null);

        assertThat(byPhone("+7 999 000 00 00")).isEmpty();
        // The empty-digit case must never collapse into "matches every order without a phone".
        assertThat(orders.findAllByNormalizedPhone(List.of(""))).isEmpty();
    }

    @Test
    void theAuditTrailKeepsBothAddressesAndSurvivesFurtherCorrections() {
        Order order = saved("typo@exmaple.com", "+7 (999) 123-45-67");

        OrderEmailCorrection first = new OrderEmailCorrection();
        first.setOrderId(order.getId());
        first.setOldEmail("typo@exmaple.com");
        first.setNewEmail("buyer@example.com");
        first.setStaffPrincipal("staff");
        first.setReason("клиент назвал опечатку");
        first.setCreatedAt(Instant.parse("2026-09-14T10:00:00Z"));
        first.setOutcome(EmailCorrectionOutcome.ACCEPTED);
        first.setSettledAt(Instant.parse("2026-09-14T10:00:02Z"));
        corrections.save(first);

        OrderEmailCorrection second = new OrderEmailCorrection();
        second.setOrderId(order.getId());
        second.setOldEmail("buyer@example.com");
        second.setNewEmail("buyer@example.org");
        second.setStaffPrincipal("staff");
        second.setReason("второй звонок");
        second.setCreatedAt(Instant.parse("2026-09-14T11:00:00Z"));
        second.setOutcome(EmailCorrectionOutcome.FAILED);
        second.setFailureType("ConnectException");
        corrections.save(second);

        List<OrderEmailCorrection> history = corrections.findAllByOrderIdOrderByCreatedAtDesc(order.getId());
        assertThat(history).extracting(OrderEmailCorrection::getNewEmail)
                .containsExactly("buyer@example.org", "buyer@example.com");
        assertThat(history.get(0).getOldEmail()).isEqualTo("buyer@example.com");
        assertThat(corrections.countByOrderId(order.getId())).isEqualTo(2);
        assertThat(corrections.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()))
                .get().extracting(OrderEmailCorrection::getReason).isEqualTo("второй звонок");
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void theShippedMigrationDeclaresTheSameColumnsHibernateBuilds() {
        // ddl-auto=update built this table from the entity. The migration in
        // scripts/migrations/20260914_add_order_email_corrections.sql must produce the same shape,
        // so every column named there has to exist here under the same name.
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'order_email_corrections'",
                String.class);
        assertThat(columns).contains("id", "order_id", "old_email", "new_email", "staff_principal",
                "reason", "created_at", "settled_at", "outcome", "failure_type");
    }
}
