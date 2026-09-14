package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.SupportContactKind;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.SupportStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SupportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    private final SupportInquiryRepository repository = mock(SupportInquiryRepository.class);
    private final SupportService service = new SupportService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private final SupportValidation.Contact contact =
            new SupportValidation.Contact(SupportContactKind.EMAIL, "Guest@example.ru", "guest@example.ru");

    private void noDuplicates() {
        when(repository.findByReference(anyString())).thenReturn(Optional.empty());
        when(repository.findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any(SupportInquiry.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aWebsiteInquiryIsStoredWithARandomReferenceAndNoOrderLookup() {
        noDuplicates();

        SupportInquiry inquiry = service.submitFromWebsite("во сколько отправление в субботу?", contact, "12345");

        assertThat(inquiry.getReference()).matches("^WT-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$");
        assertThat(inquiry.getSource()).isEqualTo(SupportSource.WEBSITE);
        assertThat(inquiry.getStatus()).isEqualTo(SupportStatus.NEW);
        assertThat(inquiry.getContact()).isEqualTo("Guest@example.ru");
        assertThat(inquiry.getContactKind()).isEqualTo(SupportContactKind.EMAIL);
        // Kept verbatim as the customer's claim; never resolved against a real order.
        assertThat(inquiry.getOrderReference()).isEqualTo("12345");
        assertThat(inquiry.getTelegramChatId()).isNull();
        assertThat(inquiry.getCreatedAt()).isEqualTo(NOW);
        assertThat(inquiry.getNotifyAttempts()).isZero();
    }

    @Test
    void twoInquiriesDoNotShareAReference() {
        noDuplicates();

        String first = service.submitFromWebsite("первый вопрос про билеты", contact, null).getReference();
        String second = service.submitFromWebsite("второй вопрос про катер", contact, null).getReference();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aRepeatedSubmissionReturnsTheExistingInquiryInsteadOfNotifyingTwice() {
        SupportInquiry existing = new SupportInquiry();
        existing.setReference("WT-AAAAAAAA");
        when(repository.findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(existing));

        SupportInquiry result = service.submitFromWebsite("тот же самый вопрос", contact, null);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any(SupportInquiry.class));
    }

    @Test
    void aTelegramInquiryStoresTheAskingChatAsTheOnlyReplyChannel() {
        noDuplicates();

        SupportInquiry inquiry = service.submitFromTelegram(4242L, "можно ли с собакой на борт?");

        assertThat(inquiry.getSource()).isEqualTo(SupportSource.TELEGRAM);
        assertThat(inquiry.getTelegramChatId()).isEqualTo(4242L);
        assertThat(inquiry.getContactKind()).isEqualTo(SupportContactKind.TELEGRAM);
        assertThat(inquiry.getContact()).isNull();
    }

    @Test
    void theDedupeKeySeparatesChannelsContactsAndTexts() {
        String base = SupportService.fingerprint("web", "guest@example.ru", "вопрос");

        assertThat(base).isEqualTo(SupportService.fingerprint("web", "guest@example.ru", "вопрос"));
        assertThat(base).isNotEqualTo(SupportService.fingerprint("tg", "guest@example.ru", "вопрос"));
        assertThat(base).isNotEqualTo(SupportService.fingerprint("web", "other@example.ru", "вопрос"));
        assertThat(base).isNotEqualTo(SupportService.fingerprint("web", "guest@example.ru", "другой вопрос"));
        // Hashed, so the column cannot become a second copy of the contact or the question.
        assertThat(base).doesNotContain("guest").doesNotContain("вопрос").hasSize(64);
    }

    @Test
    void anAcceptedNotificationMarksTheInquiryNotifiedAndClearsTheRetry() {
        SupportInquiry inquiry = storedInquiry();
        inquiry.setNextNotifyAt(NOW);
        inquiry.setLastNotifyError("Timeout");

        service.recordNotificationOutcome(inquiry.getId(), true, null, null);

        assertThat(inquiry.getStatus()).isEqualTo(SupportStatus.NOTIFIED);
        assertThat(inquiry.getNotifiedAt()).isEqualTo(NOW);
        assertThat(inquiry.getNextNotifyAt()).isNull();
        assertThat(inquiry.getLastNotifyError()).isNull();
        assertThat(inquiry.getNotifyAttempts()).isEqualTo(1);
    }

    @Test
    void aFailureWithNoRetryLeftMarksTheInquiryUndelivered() {
        SupportInquiry inquiry = storedInquiry();

        service.recordNotificationOutcome(inquiry.getId(), false, NOW.plusSeconds(60), "SocketTimeoutException");
        assertThat(inquiry.getStatus()).isEqualTo(SupportStatus.NEW);
        assertThat(inquiry.getNextNotifyAt()).isEqualTo(NOW.plusSeconds(60));

        service.recordNotificationOutcome(inquiry.getId(), false, null, "SocketTimeoutException");
        assertThat(inquiry.getStatus()).isEqualTo(SupportStatus.UNDELIVERED);
        assertThat(inquiry.getLastNotifyError()).isEqualTo("SocketTimeoutException");
        assertThat(inquiry.getNotifyAttempts()).isEqualTo(2);
    }

    @Test
    void markAnsweredRecordsWhenTheOwnersReplyWasAccepted() {
        SupportInquiry inquiry = storedInquiry();

        service.markAnswered(inquiry.getId());

        assertThat(inquiry.getStatus()).isEqualTo(SupportStatus.ANSWERED);
        assertThat(inquiry.getAnsweredAt()).isEqualTo(NOW);
    }

    @Test
    void aReferenceLookupIsCaseInsensitiveAndIgnoresBlanks() {
        SupportInquiry inquiry = storedInquiry();
        inquiry.setReference("WT-ABCD2345");
        when(repository.findByReference("WT-ABCD2345")).thenReturn(Optional.of(inquiry));

        assertThat(service.findByReference(" wt-abcd2345 ")).contains(inquiry);
        assertThat(service.findByReference("  ")).isEmpty();
        assertThat(service.findByReference(null)).isEmpty();
    }

    private SupportInquiry storedInquiry() {
        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setId(UUID.randomUUID());
        inquiry.setReference("WT-ABCD2345");
        inquiry.setStatus(SupportStatus.NEW);
        inquiry.setSource(SupportSource.TELEGRAM);
        inquiry.setCreatedAt(NOW);
        when(repository.findById(inquiry.getId())).thenReturn(Optional.of(inquiry));
        when(repository.findByReference(inquiry.getReference())).thenReturn(Optional.of(inquiry));
        when(repository.save(any(SupportInquiry.class))).thenAnswer(call -> call.getArgument(0));
        return inquiry;
    }
}
