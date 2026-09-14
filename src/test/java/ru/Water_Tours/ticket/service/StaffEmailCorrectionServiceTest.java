package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.Water_Tours.enums.EmailCorrectionOutcome;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderEmailCorrection;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderEmailCorrectionRepository;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * No message leaves this test: the send is a mock. What is under test is who may correct a
 * delivery address, what the order looks like afterwards, and what the audit trail records -
 * including when the mail server refuses the message.
 */
class StaffEmailCorrectionServiceTest {

    static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    static final String OLD_EMAIL = "buyer@exmaple.com";
    static final String NEW_EMAIL = "buyer@example.com";

    final OrderRepository orders = mock(OrderRepository.class);
    final TicketRepository tickets = mock(TicketRepository.class);
    final OrderEmailCorrectionRepository corrections = mock(OrderEmailCorrectionRepository.class);
    final TicketEmailService mail = mock(TicketEmailService.class);
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    Order order;
    Ticket ticket;

    @BeforeEach
    void setup() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(corrections.save(any(OrderEmailCorrection.class))).thenAnswer(i -> {
            OrderEmailCorrection saved = i.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(corrections.findFirstByOrderIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(corrections.countByOrderId(any())).thenReturn(0L);

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        order.setEmail(OLD_EMAIL);
        order.setPaidAt(NOW.minus(Duration.ofHours(2)));
        order.setTicketIssuedAt(NOW.minus(Duration.ofHours(2)));
        order.setTicketsEmailedAt(NOW.minus(Duration.ofHours(2)));
        when(orders.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTicketStatus(TicketStatus.ISSUED);
        ticket.setTicketType(TicketType.ADULT);
        when(tickets.findAllByOrderId(order.getId())).thenReturn(List.of(ticket));

        when(corrections.findById(any())).thenAnswer(i -> Optional.empty());
    }

    private StaffEmailCorrectionService serviceAt(Instant now) {
        return new StaffEmailCorrectionService(orders, tickets, corrections, mail, transactionManager,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(60), 3);
    }

    private OrderEmailCorrection captureAudit() {
        ArgumentCaptor<OrderEmailCorrection> captor = ArgumentCaptor.forClass(OrderEmailCorrection.class);
        verify(corrections, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    /** Makes the audit row written before the send findable again when the outcome is recorded. */
    private void trackAudit() {
        when(corrections.save(any(OrderEmailCorrection.class))).thenAnswer(i -> {
            OrderEmailCorrection saved = i.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            when(corrections.findById(saved.getId())).thenReturn(Optional.of(saved));
            return saved;
        });
    }

    @Test
    void aSuccessfulCorrectionSendsTheExistingPdfAndRecordsBothAddresses() {
        trackAudit();

        StaffEmailCorrectionService.Result result = serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "клиент назвал опечатку", "staff");

        assertThat(result.delivered()).isTrue();
        verify(mail).sendIssuedTicketsPdf(order.getId(), NEW_EMAIL);
        assertThat(order.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(order.getTicketsEmailedAt()).isEqualTo(NOW);
        assertThat(order.getTicketsEmailClaimedAt()).isNull();

        OrderEmailCorrection audit = captureAudit();
        assertThat(audit.getOldEmail()).isEqualTo(OLD_EMAIL);
        assertThat(audit.getNewEmail()).isEqualTo(NEW_EMAIL);
        assertThat(audit.getStaffPrincipal()).isEqualTo("staff");
        assertThat(audit.getReason()).isEqualTo("клиент назвал опечатку");
        assertThat(audit.getCreatedAt()).isEqualTo(NOW);
        assertThat(audit.getOutcome()).isEqualTo(EmailCorrectionOutcome.ACCEPTED);
    }

    @Test
    void aRefusedSendLeavesTheCorrectedAddressRetryableAndRecordsTheFailure() {
        trackAudit();
        doThrow(new TicketEmailException("smtp down", new java.net.ConnectException("refused")))
                .when(mail).sendIssuedTicketsPdf(any(), any());

        StaffEmailCorrectionService.Result result = serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff");

        assertThat(result.delivered()).isFalse();
        // Corrected address kept, no delivery stamp, claim released: the ordinary delivery path
        // sees an order that still has to be sent, to the right address this time.
        assertThat(order.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(order.getTicketsEmailedAt()).isNull();
        assertThat(order.getTicketsEmailClaimedAt()).isNull();

        OrderEmailCorrection audit = captureAudit();
        assertThat(audit.getOutcome()).isEqualTo(EmailCorrectionOutcome.FAILED);
        assertThat(audit.getFailureType()).isEqualTo("ConnectException");
        assertThat(audit.getOldEmail()).isEqualTo(OLD_EMAIL);
        assertThat(audit.getNewEmail()).isEqualTo(NEW_EMAIL);
    }

    @Test
    void theAuditRowExistsBeforeTheSendIsAttempted() {
        doAnswer(invocation -> {
            // At this point the send has not returned yet; the correction must already be recorded.
            OrderEmailCorrection pending = captureAudit();
            assertThat(pending.getOutcome()).isEqualTo(EmailCorrectionOutcome.PENDING);
            assertThat(pending.getSettledAt()).isNull();
            return null;
        }).when(mail).sendIssuedTicketsPdf(any(), any());

        serviceAt(NOW).correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff");
    }

    @Test
    void aSendAlreadyInFlightBlocksTheCorrection() {
        order.setTicketsEmailClaimedAt(NOW.minus(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже отправляется");

        verifyNoInteractions(mail);
        assertThat(order.getEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void correctionsAreRateLimitedAndCappedPerOrder() {
        OrderEmailCorrection recent = new OrderEmailCorrection();
        recent.setCreatedAt(NOW.minus(Duration.ofSeconds(20)));
        when(corrections.findFirstByOrderIdOrderByCreatedAtDesc(order.getId())).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Слишком часто");

        when(corrections.countByOrderId(order.getId())).thenReturn(3L);
        assertThatThrownBy(() -> serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("предел исправлений");

        verifyNoInteractions(mail);
    }

    @Test
    void inputThatCouldSendTheTicketToTheWrongPlaceIsRefused() {
        StaffEmailCorrectionService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.correctAndResend(order.getId(), "not-an-email", "not-an-email", "опечатка", "staff"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.correctAndResend(order.getId(), NEW_EMAIL, "typo@example.com", "опечатка", "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не совпадают");
        assertThatThrownBy(() -> service.correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "  ", "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("причину");
        assertThatThrownBy(() -> service.correctAndResend(order.getId(), OLD_EMAIL, OLD_EMAIL, "опечатка", "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уже указан");

        verifyNoInteractions(mail);
        assertThat(order.getEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void onlyAPaidOrderWithLiveTicketsMayHaveItsAddressCorrected() {
        StaffEmailCorrectionService service = serviceAt(NOW);

        ticket.setTicketStatus(TicketStatus.USED);
        assertThat(service.ineligibilityReason(order, List.of(ticket))).get().asString().contains("использован");

        ticket.setTicketStatus(TicketStatus.REVOKED);
        assertThat(service.ineligibilityReason(order, List.of(ticket))).get().asString().contains("аннулирован");

        ticket.setTicketStatus(TicketStatus.ISSUED);
        order.setRefundPendingAt(NOW);
        assertThat(service.ineligibilityReason(order, List.of(ticket))).get().asString().contains("возврат");

        order.setRefundPendingAt(null);
        order.setStatus(OrderStatus.REFUNDED);
        assertThat(service.ineligibilityReason(order, List.of(ticket))).get().asString().contains("возврат");

        order.setStatus(OrderStatus.PENDING_PAYMENT);
        assertThat(service.ineligibilityReason(order, List.of(ticket))).get().asString().contains("не оплачен");

        order.setStatus(OrderStatus.PAID);
        assertThat(service.ineligibilityReason(order, List.of())).get().asString().contains("не выпущены");

        assertThat(service.ineligibilityReason(order, List.of(ticket))).isEmpty();
    }

    @Test
    void anIneligibleOrderIsRefusedAtSubmissionToo() {
        ticket.setTicketStatus(TicketStatus.USED);

        assertThatThrownBy(() -> serviceAt(NOW)
                .correctAndResend(order.getId(), NEW_EMAIL, NEW_EMAIL, "опечатка", "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("использован");

        verify(mail, never()).sendIssuedTicketsPdf(any(), eq(NEW_EMAIL));
        assertThat(order.getEmail()).isEqualTo(OLD_EMAIL);
    }
}
