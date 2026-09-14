package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.SupportStatus;
import ru.Water_Tours.telegram.TelegramSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportNotificationJobTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final long OWNER = 777L;

    private final SupportInquiryRepository repository = mock(SupportInquiryRepository.class);
    private final SupportService service = mock(SupportService.class);
    private final TelegramSender sender = mock(TelegramSender.class);

    private SupportNotificationJob job(SupportProperties properties) {
        return new SupportNotificationJob(repository, service, properties, sender,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SupportInquiry inquiry(int previousFailures) {
        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setId(UUID.randomUUID());
        inquiry.setReference("WT-ABCD2345");
        inquiry.setSource(SupportSource.WEBSITE);
        inquiry.setStatus(SupportStatus.NEW);
        inquiry.setContact("guest@example.ru");
        inquiry.setMessage("Во сколько отправление в субботу?");
        inquiry.setCreatedAt(NOW);
        inquiry.setNotifyAttempts(previousFailures);
        when(repository.findDueForNotification(eq(SupportStatus.NEW), any(), any(Pageable.class)))
                .thenReturn(List.of(inquiry));
        return inquiry;
    }

    @Test
    void anAcceptedNotificationGoesOnlyToTheConfiguredOwner() {
        SupportInquiry inquiry = inquiry(0);
        when(sender.sendMessageChecked(anyLong(), anyString())).thenReturn(TelegramSender.DeliveryResult.ok());

        job(new SupportProperties(true, String.valueOf(OWNER))).notifyOwner();

        verify(sender).sendMessageChecked(eq(OWNER), contains("WT-ABCD2345"));
        verifyNoMoreInteractions(sender);
        verify(service).recordNotificationOutcome(inquiry.getId(), true, null, null);
    }

    @Test
    void theOwnerMessageCarriesTheReplyContactAndSaysTheBotCannotUseIt() {
        inquiry(0);
        when(sender.sendMessageChecked(anyLong(), anyString())).thenReturn(TelegramSender.DeliveryResult.ok());

        job(new SupportProperties(true, String.valueOf(OWNER))).notifyOwner();

        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessageChecked(eq(OWNER), text.capture());
        assertThat(text.getValue())
                .contains("guest@example.ru")
                .contains("Во сколько отправление в субботу?")
                .contains("бот не может написать на почту или телефон");
    }

    @Test
    void aRefusedNotificationIsScheduledForRetryAndNotResent() {
        SupportInquiry inquiry = inquiry(0);
        when(sender.sendMessageChecked(anyLong(), anyString()))
                .thenReturn(TelegramSender.DeliveryResult.failed("SocketTimeoutException"));

        job(new SupportProperties(true, String.valueOf(OWNER))).notifyOwner();

        verify(sender, times(1)).sendMessageChecked(anyLong(), anyString());
        verify(service).recordNotificationOutcome(inquiry.getId(), false,
                NOW.plus(Duration.ofMinutes(1)), "SocketTimeoutException");
    }

    @Test
    void retriesAreBoundedAndTheInquiryEndsUpVisibleAsUndelivered() {
        SupportInquiry inquiry = inquiry(5);
        when(sender.sendMessageChecked(anyLong(), anyString()))
                .thenReturn(TelegramSender.DeliveryResult.failed("TelegramError403"));

        job(new SupportProperties(true, String.valueOf(OWNER))).notifyOwner();

        // A null next attempt is what SupportService turns into UNDELIVERED - no endless retry.
        verify(service).recordNotificationOutcome(inquiry.getId(), false, null, "TelegramError403");
    }

    @Test
    void aThrowingSenderIsTreatedAsAFailureRatherThanBreakingThePass() {
        SupportInquiry inquiry = inquiry(0);
        when(sender.sendMessageChecked(anyLong(), anyString())).thenThrow(new IllegalStateException("boom"));

        job(new SupportProperties(true, String.valueOf(OWNER))).notifyOwner();

        verify(service).recordNotificationOutcome(eq(inquiry.getId()), eq(false), any(), eq("IllegalStateException"));
    }

    @Test
    void withNoOwnerConfiguredNothingIsSentAnywhere() {
        job(new SupportProperties(true, "")).notifyOwner();

        verifyNoInteractions(sender);
        verifyNoInteractions(repository);
        verifyNoInteractions(service);
    }
}
