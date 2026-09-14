package ru.Water_Tours;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.Water_Tours.enums.SupportContactKind;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.SupportStatus;
import ru.Water_Tours.support.SupportInquiry;
import ru.Water_Tours.support.SupportInquiryRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The support table is new, and two of its guarantees live in the schema rather than in Java:
 * a reference must be unique (it is the only handle /reply accepts) and the notification job's
 * "what is still waiting" query must actually select the right rows. Both are checked against a
 * real PostgreSQL so a column Hibernate maps differently from the migration cannot slip through.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "yookassa.shopId=test", "yookassa.secretKey=test", "app.base-url=http://localhost:8080",
        "spring.mail.username=test@example.invalid", "spring.mail.password=test",
        "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
        "management.health.mail.enabled=false", "payments.reconciliation.enabled=false",
        "tickets.issuance.enabled=false", "order.expiration-check-interval=86400000",
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "support.owner-telegram-chat-id="
})
@Testcontainers
class SupportInquiryPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired SupportInquiryRepository inquiries;

    @BeforeEach
    void clean() {
        inquiries.deleteAll();
    }

    private SupportInquiry inquiry(String reference, SupportStatus status, Instant createdAt) {
        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setReference(reference);
        inquiry.setSource(SupportSource.WEBSITE);
        inquiry.setContactKind(SupportContactKind.EMAIL);
        inquiry.setStatus(status);
        inquiry.setMessage("Во сколько отправление в субботу?");
        inquiry.setContact("guest@example.ru");
        inquiry.setCreatedAt(createdAt);
        return inquiry;
    }

    @Test
    void aReferenceCannotBeReused() {
        inquiries.saveAndFlush(inquiry("WT-ABCD2345", SupportStatus.NEW, Instant.parse("2026-09-14T10:00:00Z")));

        assertThatThrownBy(() -> inquiries.saveAndFlush(
                inquiry("WT-ABCD2345", SupportStatus.NEW, Instant.parse("2026-09-14T11:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFullLengthQuestionAndContactRoundTrip() {
        SupportInquiry stored = inquiry("WT-LONG2345", SupportStatus.NEW, Instant.parse("2026-09-14T10:00:00Z"));
        stored.setMessage("я".repeat(SupportInquiry.MAX_MESSAGE_LENGTH));
        stored.setContact("c".repeat(SupportInquiry.MAX_CONTACT_LENGTH - 12) + "@example.ru");
        stored.setOrderReference("o".repeat(SupportInquiry.MAX_ORDER_REFERENCE_LENGTH));

        inquiries.saveAndFlush(stored);
        SupportInquiry back = inquiries.findByReference("WT-LONG2345").orElseThrow();

        assertThat(back.getMessage()).hasSize(SupportInquiry.MAX_MESSAGE_LENGTH);
        assertThat(back.getContact()).hasSize(SupportInquiry.MAX_CONTACT_LENGTH - 1);
        assertThat(back.getOrderReference()).hasSize(SupportInquiry.MAX_ORDER_REFERENCE_LENGTH);
    }

    @Test
    void onlyDueNewInquiriesAreSelectedForNotification() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");

        inquiries.save(inquiry("WT-NEWNOW11", SupportStatus.NEW, now.minusSeconds(60)));

        SupportInquiry backingOff = inquiry("WT-LATER222", SupportStatus.NEW, now.minusSeconds(120));
        backingOff.setNextNotifyAt(now.plusSeconds(600));
        inquiries.save(backingOff);

        inquiries.save(inquiry("WT-DONE3333", SupportStatus.NOTIFIED, now.minusSeconds(180)));
        inquiries.save(inquiry("WT-DEAD4444", SupportStatus.UNDELIVERED, now.minusSeconds(240)));

        List<SupportInquiry> due = inquiries.findDueForNotification(SupportStatus.NEW, now, PageRequest.of(0, 10));

        assertThat(due).extracting(SupportInquiry::getReference).containsExactly("WT-NEWNOW11");
    }

    @Test
    void theBatchSizeIsHonouredSoABacklogCannotBurst() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        for (int i = 0; i < 12; i++) {
            inquiries.save(inquiry("WT-BULK" + String.format("%04d", i), SupportStatus.NEW, now.minusSeconds(600 - i)));
        }

        assertThat(inquiries.findDueForNotification(SupportStatus.NEW, now, PageRequest.of(0, 10))).hasSize(10);
    }

    @Test
    void aDuplicateIsFoundOnlyInsideTheWindow() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        SupportInquiry first = inquiry("WT-DUPE1111", SupportStatus.NEW, now.minusSeconds(60));
        first.setDedupeHash("hash-1");
        inquiries.save(first);

        assertThat(inquiries.findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc("hash-1", now.minusSeconds(600)))
                .isPresent();
        assertThat(inquiries.findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc("hash-1", now.minusSeconds(10)))
                .isEmpty();
        assertThat(inquiries.findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc("hash-2", now.minusSeconds(600)))
                .isEmpty();
    }
}
