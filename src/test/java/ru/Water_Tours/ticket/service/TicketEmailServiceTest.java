package ru.Water_Tours.ticket.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * No message ever leaves this test: the mail sender is a mock. What is under test is who is
 * allowed to ask for a resend, how often, and what the order records afterwards.
 */
class TicketEmailServiceTest {
    static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    static final Duration COOLDOWN = Duration.ofMinutes(2);

    final JavaMailSender mailSender = mock(JavaMailSender.class);
    final OrderRepository orders = mock(OrderRepository.class);
    final PdfTicketService pdf = mock(PdfTicketService.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    Order order;

    @BeforeEach
    void setup() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(pdf.buildTicketsPdfByOrderId(any(), any())).thenReturn(new byte[]{1, 2, 3});

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        order.setEmail("buyer@example.com");
        order.setPaidAt(NOW.minus(Duration.ofHours(1)));
        order.setTicketIssuedAt(NOW.minus(Duration.ofMinutes(59)));
        order.setTicketsEmailedAt(NOW.minus(Duration.ofMinutes(58)));
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    }

    private TicketEmailService serviceAt(Instant now) {
        return new TicketEmailService(mailSender, orders, pdf, transactionManager,
                Clock.fixed(now, ZoneOffset.UTC), "http://localhost:8080", "tickets@example.com",
                COOLDOWN, 2);
    }

    @Test
    void firstDeliveryIsSkippedOnceTheOrderWasAlreadyEmailed() {
        serviceAt(NOW).sendTicketsPdf(order.getId());

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void firstDeliverySendsAndRecordsTheDeliveryTime() {
        order.setTicketsEmailedAt(null);

        serviceAt(NOW).sendTicketsPdf(order.getId());

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailedAt()).isEqualTo(NOW);
        assertThat(order.getTicketsEmailAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void resendSendsAgainAndCountsTheAttempt() {
        serviceAt(NOW).resendTicketsPdf(order.getId());

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailAttempts()).isEqualTo(1);
        assertThat(order.getTicketsEmailedAt()).isEqualTo(NOW);
    }

    @Test
    void resendInsideTheCooldownIsRefusedWithoutSendingAnything() {
        order.setTicketsEmailAttemptAt(NOW.minus(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> serviceAt(NOW).resendTicketsPdf(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Повторная отправка");

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailAttempts()).isNull();
    }

    @Test
    void resendStopsAtTheConfiguredAttemptCap() {
        order.setTicketsEmailAttempts(2);
        order.setTicketsEmailAttemptAt(NOW.minus(Duration.ofHours(2)));

        assertThatThrownBy(() -> serviceAt(NOW).resendTicketsPdf(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("предел");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aFailedResendIsReportedAsRetryableAndDoesNotClaimDelivery() {
        Instant emailedBefore = order.getTicketsEmailedAt();
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> serviceAt(NOW).resendTicketsPdf(order.getId()))
                .isInstanceOf(TicketEmailException.class);

        assertThat(order.getTicketsEmailedAt()).isEqualTo(emailedBefore);
        assertThat(order.getTicketsEmailAttempts()).isEqualTo(1);
        assertThat(order.getTicketsEmailAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void aRetryAfterTheCooldownSucceedsAndRecordsDelivery() {
        order.setTicketsEmailAttempts(1);
        order.setTicketsEmailAttemptAt(NOW);
        Instant later = NOW.plus(COOLDOWN).plusSeconds(1);

        serviceAt(later).resendTicketsPdf(order.getId());

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailAttempts()).isEqualTo(2);
        assertThat(order.getTicketsEmailedAt()).isEqualTo(later);
    }

    @Test
    void resendIsRefusedBeforeTicketsExist() {
        order.setTicketIssuedAt(null);

        assertThatThrownBy(() -> serviceAt(NOW).resendTicketsPdf(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не выпущены");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void resendIsRefusedForAnOrderThatIsNotPaid() {
        order.setStatus(OrderStatus.REFUNDED);

        assertThatThrownBy(() -> serviceAt(NOW).resendTicketsPdf(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не оплачен");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aSecondDeliveryRunSkipsAnOrderThatIsAlreadyBeingSent() {
        order.setTicketsEmailedAt(null);
        order.setTicketsEmailClaimedAt(NOW.minus(Duration.ofSeconds(5)));

        serviceAt(NOW).sendTicketsPdf(order.getId());

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailedAt()).isNull();
    }

    @Test
    void aClaimLeftBehindByACrashIsReclaimedAfterTheLease() {
        order.setTicketsEmailedAt(null);
        order.setTicketsEmailClaimedAt(NOW.minus(Duration.ofMinutes(11)));

        serviceAt(NOW).sendTicketsPdf(order.getId());

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(order.getTicketsEmailedAt()).isEqualTo(NOW);
        assertThat(order.getTicketsEmailClaimedAt()).isNull();
    }

    @Test
    void aFailedAttemptReleasesTheClaimSoTheNextRunCanRetry() {
        order.setTicketsEmailedAt(null);
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> serviceAt(NOW).sendTicketsPdf(order.getId()))
                .isInstanceOf(TicketEmailException.class);

        assertThat(order.getTicketsEmailClaimedAt()).isNull();
        assertThat(order.getTicketsEmailedAt()).isNull();
    }

    @Test
    void theMailServerIsNeverContactedWhileTheOrderRowIsLocked() {
        order.setTicketsEmailedAt(null);

        serviceAt(NOW).sendTicketsPdf(order.getId());

        // Reserve, send, record: the send sits between two separate short transactions.
        verify(transactionManager, times(2)).getTransaction(any());
    }
}
