package ru.Water_Tours.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.Water_Tours.enums.SupportStatus;
import ru.Water_Tours.telegram.TelegramSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivers stored inquiries to the owner, and retries the ones Telegram refused.
 *
 * Retry is bounded in both directions. Each inquiry advances through a fixed backoff and is
 * marked UNDELIVERED after the last attempt instead of being retried forever, and one pass takes
 * at most {@link #BATCH_SIZE} inquiries so a backlog cannot turn into a burst of messages. An
 * inquiry leaves NEW the moment Telegram accepts it, so the owner never receives it twice.
 */
@Component
public class SupportNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(SupportNotificationJob.class);

    private static final int BATCH_SIZE = 10;

    /**
     * Delay before attempt N+1. Its length is also the attempt cap: once an inquiry has used the
     * last entry it is UNDELIVERED and the owner recovers it from /staff/support-inquiries.
     */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(3),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(2)
    };

    private final SupportInquiryRepository repository;
    private final SupportService supportService;
    private final SupportProperties properties;
    private final TelegramSender sender;
    private final Clock clock;

    public SupportNotificationJob(SupportInquiryRepository repository, SupportService supportService,
                                  SupportProperties properties, TelegramSender sender, Clock clock) {
        this.repository = repository;
        this.supportService = supportService;
        this.properties = properties;
        this.sender = sender;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${support.notify-interval:20000}",
            initialDelayString = "${support.notify-interval:20000}")
    public void notifyOwner() {
        if (!properties.isEnabled()) {
            return;
        }
        List<SupportInquiry> due = repository.findDueForNotification(
                SupportStatus.NEW, clock.instant(), PageRequest.of(0, BATCH_SIZE));
        for (SupportInquiry inquiry : due) {
            deliver(inquiry);
        }
    }

    private void deliver(SupportInquiry inquiry) {
        TelegramSender.DeliveryResult result;
        try {
            result = sender.sendMessageChecked(properties.getOwnerChatId(), SupportMessages.ownerNotification(inquiry));
        } catch (Exception e) {
            result = TelegramSender.DeliveryResult.failed(e.getClass().getSimpleName());
        }

        if (result.accepted()) {
            supportService.recordNotificationOutcome(inquiry.getId(), true, null, null);
            log.info("Support inquiry notified, reference={}", inquiry.getReference());
            return;
        }

        int failuresBefore = inquiry.getNotifyAttempts();
        int attemptsAfterThisOne = failuresBefore + 1;
        Instant next = failuresBefore < BACKOFF.length
                ? clock.instant().plus(BACKOFF[failuresBefore])
                : null;
        supportService.recordNotificationOutcome(inquiry.getId(), false, next, result.errorType());
        if (next == null) {
            log.warn("Support inquiry undelivered after {} attempts, reference={}, lastError={}",
                    attemptsAfterThisOne, inquiry.getReference(), result.errorType());
        }
    }
}
