package ru.Water_Tours.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.Water_Tours.enums.EmailCorrectionOutcome;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderEmailCorrection;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderEmailCorrectionRepository;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Support action for one specific case: the customer mistyped their address at checkout, so the
 * ticket e-mail went somewhere they cannot read.
 *
 * What it does is deliberately narrow. It changes where the ticket is sent and re-sends the PDF
 * that was already issued. It does not touch payment, does not issue a ticket or a QR code, does
 * not create an order, and never reveals an access token.
 *
 * Ordering is the reason this is not one transaction: validate, then apply the correction and
 * reserve the delivery claim in a short transaction, then talk to the mail server with no locks
 * held, then record the outcome. The audit row is written BEFORE the send, so a process that dies
 * mid-send still leaves evidence that the address was changed, by whom and why.
 *
 * Failure semantics: a failed send leaves the order with the corrected address and no delivery
 * stamp - exactly the state the ordinary delivery path treats as "still to send". The corrected
 * destination therefore stays retryable, and the audit row keeps both the old and the new value.
 */
@Service
public class StaffEmailCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(StaffEmailCorrectionService.class);

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final int REASON_MIN = 3;
    private static final int REASON_MAX = 300;

    /** What the staff page reports afterwards. `delivered` is SMTP acceptance, nothing more. */
    public record Result(boolean delivered, String message) {
    }

    private record Claim(UUID auditId, String recipient) {
    }

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final OrderEmailCorrectionRepository correctionRepository;
    private final TicketEmailService ticketEmailService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration cooldown;
    private final int maxPerOrder;

    public StaffEmailCorrectionService(OrderRepository orderRepository,
                                       TicketRepository ticketRepository,
                                       OrderEmailCorrectionRepository correctionRepository,
                                       TicketEmailService ticketEmailService,
                                       PlatformTransactionManager transactions,
                                       Clock clock,
                                       @Value("${staff.email-correction.cooldown:60s}") Duration cooldown,
                                       @Value("${staff.email-correction.max-per-order:3}") int maxPerOrder) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.correctionRepository = correctionRepository;
        this.ticketEmailService = ticketEmailService;
        this.clock = clock;
        this.cooldown = cooldown;
        this.maxPerOrder = maxPerOrder;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Why this order cannot have its delivery address corrected, or empty when it can. Used both
     * to decide whether to show the form and to refuse the submission, so the screen and the
     * action can never disagree about eligibility.
     */
    public Optional<String> ineligibilityReason(Order order, List<Ticket> tickets) {
        if (order.getStatus() == OrderStatus.REFUNDED) {
            return Optional.of("По заказу выполнен возврат — билет недействителен.");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            return Optional.of("Заказ не оплачен — отправлять нечего.");
        }
        if (order.getRefundPendingAt() != null) {
            return Optional.of("По заказу выполняется возврат — отправка заблокирована до результата.");
        }
        if (order.getTicketIssuedAt() == null || tickets.isEmpty()) {
            return Optional.of("Билеты по заказу ещё не выпущены.");
        }
        if (tickets.stream().anyMatch(t -> t.getTicketStatus() == TicketStatus.USED)) {
            return Optional.of("Билет уже использован — повторная отправка недоступна.");
        }
        if (tickets.stream().anyMatch(t -> t.getTicketStatus() == TicketStatus.REVOKED)) {
            return Optional.of("Билет аннулирован — повторная отправка недоступна.");
        }
        if (tickets.stream().anyMatch(t -> t.getTicketStatus() == TicketStatus.EXPIRED)) {
            return Optional.of("Срок действия билета истёк — повторная отправка недоступна.");
        }
        if (correctionRepository.countByOrderId(order.getId()) >= maxPerOrder) {
            return Optional.of("Достигнут предел исправлений адреса по этому заказу (" + maxPerOrder + ").");
        }
        return Optional.empty();
    }

    public int maxPerOrder() {
        return maxPerOrder;
    }

    public Duration cooldown() {
        return cooldown;
    }

    /**
     * Applies the corrected address and re-sends the existing ticket PDF.
     *
     * @throws IllegalArgumentException on input the operator can fix (address, confirmation, reason)
     * @throws IllegalStateException    when the order is not in a state that allows the correction
     */
    public Result correctAndResend(UUID orderId, String newEmailRaw, String confirmEmailRaw,
                                   String reasonRaw, String staffPrincipal) {
        String newEmail = normalizeEmail(newEmailRaw);
        String confirmEmail = normalizeEmail(confirmEmailRaw);
        if (newEmail.length() > 254 || !EMAIL.matcher(newEmail).matches()) {
            throw new IllegalArgumentException("Укажите корректный email.");
        }
        if (!newEmail.equals(confirmEmail)) {
            throw new IllegalArgumentException("Адреса не совпадают. Введите новый email дважды.");
        }
        String reason = reasonRaw == null ? "" : reasonRaw.trim();
        if (reason.length() < REASON_MIN) {
            throw new IllegalArgumentException("Укажите причину исправления (не короче " + REASON_MIN + " символов).");
        }
        if (reason.length() > REASON_MAX) {
            throw new IllegalArgumentException("Причина слишком длинная (максимум " + REASON_MAX + " символов).");
        }
        String staff = staffPrincipalOf(staffPrincipal);

        Claim claim = tx.execute(status -> reserve(orderId, newEmail, reason, staff));
        if (claim == null) {
            throw new IllegalStateException("Исправление адреса не выполнено.");
        }

        boolean delivered = false;
        String failureType = null;
        try {
            ticketEmailService.sendIssuedTicketsPdf(orderId, claim.recipient());
            delivered = true;
        } catch (RuntimeException e) {
            failureType = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
            // No address in the log line: the audit row is the only place addresses are kept.
            log.warn("Staff email correction delivery failed for orderId={}, by={}, errorType={}",
                    orderId, staff, failureType);
        } finally {
            settle(orderId, claim.auditId(), delivered, failureType);
        }

        if (delivered) {
            log.info("Staff corrected the ticket email address for orderId={}, by={}, outcome=ACCEPTED", orderId, staff);
            return new Result(true, "Адрес исправлен, письмо с билетом принято почтовым сервером (SMTP). "
                    + "Это подтверждение приёма, а не доказательство доставки во «Входящие».");
        }
        return new Result(false, "Адрес исправлен, но письмо отправить не удалось. Заказ остался в состоянии "
                + "«письмо не отправлено», поэтому отправку можно повторить. Пока предложите клиенту скачать "
                + "PDF по ссылке из его заказа.");
    }

    private static String staffPrincipalOf(String raw) {
        String staff = raw == null || raw.isBlank() ? "unknown" : raw.trim();
        return staff.length() > 120 ? staff.substring(0, 120) : staff;
    }

    /**
     * Validates under a row lock, records the audit row, applies the address and takes the same
     * delivery claim the ordinary send path uses - so a correction and an automatic delivery can
     * never both be handing the mail server this order's tickets.
     */
    private Claim reserve(UUID orderId, String newEmail, String reason, String staff) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Заказ не найден: " + orderId));
        List<Ticket> tickets = ticketRepository.findAllByOrderId(orderId);
        Optional<String> blocked = ineligibilityReason(order, tickets);
        if (blocked.isPresent()) {
            throw new IllegalStateException(blocked.get());
        }

        Instant now = Instant.now(clock);
        if (TicketEmailService.claimHeld(order, now)) {
            throw new IllegalStateException("Письмо по этому заказу уже отправляется. Подождите минуту и обновите страницу.");
        }
        Optional<OrderEmailCorrection> last = correctionRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId);
        if (last.isPresent() && now.isBefore(last.get().getCreatedAt().plus(cooldown))) {
            long seconds = Math.max(1, Duration.between(now, last.get().getCreatedAt().plus(cooldown)).getSeconds());
            throw new IllegalStateException("Слишком часто. Повторите через " + seconds + " с.");
        }

        String oldEmail = order.getEmail();
        if (oldEmail != null && newEmail.equalsIgnoreCase(oldEmail.trim())) {
            throw new IllegalStateException("Этот адрес уже указан в заказе. Для повторной отправки на тот же адрес "
                    + "используйте обычную отправку письма.");
        }

        OrderEmailCorrection audit = new OrderEmailCorrection();
        audit.setOrderId(orderId);
        audit.setOldEmail(oldEmail);
        audit.setNewEmail(newEmail);
        audit.setStaffPrincipal(staff);
        audit.setReason(reason);
        audit.setCreatedAt(now);
        audit.setOutcome(EmailCorrectionOutcome.PENDING);
        correctionRepository.save(audit);

        order.setEmail(newEmail);
        // The stamp being cleared described delivery to an address the customer cannot read.
        // Keeping it would make the purchase screen tell them the letter is already on its way to
        // them, which is the very thing this action exists to correct. The old value is preserved
        // in the audit row above, so nothing is lost.
        order.setTicketsEmailedAt(null);
        order.setTicketsEmailClaimedAt(now);
        // Moves the customer's own resend cooldown so a correction and a customer resend cannot
        // fire back to back. The customer's resend budget itself is left untouched.
        order.setTicketsEmailAttemptAt(now);
        orderRepository.save(order);
        return new Claim(audit.getId(), newEmail);
    }

    private void settle(UUID orderId, UUID auditId, boolean delivered, String failureType) {
        try {
            tx.executeWithoutResult(status -> {
                Instant now = Instant.now(clock);
                Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
                order.setTicketsEmailClaimedAt(null);
                if (delivered) {
                    order.setTicketsEmailedAt(now);
                }
                orderRepository.save(order);
                correctionRepository.findById(auditId).ifPresent(audit -> {
                    audit.setOutcome(delivered ? EmailCorrectionOutcome.ACCEPTED : EmailCorrectionOutcome.FAILED);
                    audit.setSettledAt(now);
                    audit.setFailureType(failureType);
                    correctionRepository.save(audit);
                });
            });
        } catch (Exception e) {
            // The claim lease is the backstop, and the audit row stays PENDING - which is honest:
            // the outcome genuinely is not known here.
            log.warn("Could not record the email correction outcome for orderId={}, errorType={}",
                    orderId, e.getClass().getSimpleName());
        }
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
