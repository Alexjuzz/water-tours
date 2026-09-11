package ru.Water_Tours.ticket.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Sending is deliberately kept outside the database transaction: an SMTP round trip can stall for
 * the full connect plus read timeout, and holding the order row locked for that long blocks
 * redemption and refunds on the same order. The attempt is reserved in a short transaction, the
 * message goes out with no locks held, and the result is recorded in a second short transaction.
 */
@Service
public class TicketEmailService {
    private static final Logger log = LoggerFactory.getLogger(TicketEmailService.class);

    // Upper bound on how long one send may hold the claim. Only reached when a process dies
    // mid-send; a normal attempt releases the claim as soon as the mail server answers.
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private final JavaMailSender mailSender;
    private final OrderRepository orderRepository;
    private final PdfTicketService pdfTicketService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String baseUrl;
    private final String mailFrom;
    private final Duration resendCooldown;
    private final int maxResends;

    public TicketEmailService(JavaMailSender mailSender,
                              OrderRepository orderRepository,
                              PdfTicketService pdfTicketService,
                              PlatformTransactionManager transactions,
                              Clock clock,
                              @Value("${app.base-url}") String baseUrl,
                              @Value("${app.mail-from}") String mailFrom,
                              @Value("${tickets.resend.cooldown:2m}") Duration resendCooldown,
                              @Value("${tickets.resend.max-attempts:5}") int maxResends) {
        this.orderRepository = orderRepository;
        this.pdfTicketService = pdfTicketService;
        this.baseUrl = baseUrl;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.clock = clock;
        this.resendCooldown = resendCooldown;
        this.maxResends = maxResends;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** First delivery. Does nothing once the order has already been emailed. */
    public void sendTicketsPdf(UUID orderId) {
        deliver(orderId, false);
    }

    /**
     * Customer-triggered resend of an already delivered ticket email. Rate limited by a cooldown
     * and capped in total, so a reload loop or a stuck mail server cannot turn one order into an
     * unbounded stream of messages.
     */
    public void resendTicketsPdf(UUID orderId) {
        deliver(orderId, true);
    }

    private void deliver(UUID orderId, boolean resend) {
        String recipient = tx.execute(status -> reserveAttempt(orderId, resend));
        if (recipient == null) return;

        boolean delivered = false;
        try {
            byte[] pdfBytes = pdfTicketService.buildTicketsPdfByOrderId(orderId, baseUrl);
            sendEmailWithPdf(recipient, orderId, pdfBytes);
            delivered = true;
        } catch (Exception e) {
            log.warn("Ticket email attempt failed for orderId={}, errorType={}", orderId, e.getClass().getSimpleName());
            throw new TicketEmailException("Ticket email could not be sent for order " + orderId, e);
        } finally {
            releaseClaim(orderId, delivered);
        }
    }

    private void releaseClaim(UUID orderId, boolean delivered) {
        try {
            tx.executeWithoutResult(status -> {
                Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
                order.setTicketsEmailClaimedAt(null);
                if (delivered) order.setTicketsEmailedAt(Instant.now(clock));
                orderRepository.save(order);
            });
        } catch (Exception e) {
            // The claim lease is the backstop: it expires and the delivery job picks the order up
            // again rather than leaving it stuck because one write could not land.
            log.warn("Could not release the ticket email claim for orderId={}, errorType={}", orderId, e.getClass().getSimpleName());
        }
    }

    /** Returns the recipient when this attempt should go ahead, or null when there is nothing to do. */
    private String reserveAttempt(UUID orderId, boolean resend) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order with id " + orderId + " not found"));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Заказ не оплачен, билеты отправить нельзя.");
        }
        if (order.getTicketIssuedAt() == null) {
            throw new IllegalStateException("Билеты по заказу ещё не выпущены. Попробуйте через минуту.");
        }
        if (order.getEmail() == null || order.getEmail().isBlank()) {
            throw new IllegalStateException("В заказе не указан email, отправка невозможна.");
        }

        Instant now = Instant.now(clock);
        Instant claimedAt = order.getTicketsEmailClaimedAt();
        boolean claimHeld = claimedAt != null && now.isBefore(claimedAt.plus(CLAIM_LEASE));
        if (!resend) {
            if (order.getTicketsEmailedAt() != null) return null;
            // Another delivery run is already handing this order to the mail server.
            if (claimHeld) return null;
        } else {
            if (claimHeld) {
                throw new IllegalStateException("Письмо уже отправляется. Подождите минуту и проверьте почту.");
            }
            int used = order.getTicketsEmailAttempts() == null ? 0 : order.getTicketsEmailAttempts();
            if (used >= maxResends) {
                throw new IllegalStateException("Достигнут предел повторных отправок. Скачайте билет по ссылке или напишите нам.");
            }
            Instant lastAttempt = order.getTicketsEmailAttemptAt();
            if (lastAttempt != null && now.isBefore(lastAttempt.plus(resendCooldown))) {
                throw new IllegalStateException("Письмо уже отправляется. Повторная отправка будет доступна через "
                        + Math.max(1, Duration.between(now, lastAttempt.plus(resendCooldown)).toMinutes()) + " мин.");
            }
            order.setTicketsEmailAttempts(used + 1);
        }
        order.setTicketsEmailClaimedAt(now);
        order.setTicketsEmailAttemptAt(now);
        orderRepository.save(order);
        return order.getEmail();
    }

    private void sendEmailWithPdf(String to, UUID orderId, byte[] pdfBytes) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailFrom);
        helper.setTo(to);
        helper.setSubject("Ваши билеты Water Tours — оплата подтверждена");
        // Plain-text part first (fallback for clients that don't render HTML), then HTML.
        helper.setText(plainTextBody(), htmlBody());
        helper.addAttachment("tickets-" + orderId + ".pdf", new ByteArrayResource(pdfBytes));
        mailSender.send(message);
    }

    private String plainTextBody() {
        return """
                Здравствуйте!

                Спасибо за покупку билетов Water Tours — оплата прошла успешно.

                Ваши билеты во вложении (PDF с QR-кодом на каждый билет). Срок действия
                и время указаны прямо на билете. Перед посадкой покажите QR-код персоналу
                для прохода — билет одноразовый.

                Если у вас возникнут вопросы по заказу, просто ответьте на это письмо.

                Хорошей прогулки!
                Water Tours
                """;
    }

    // Inline styles only, no external CSS/images: keeps rendering consistent across mail clients.
    private String htmlBody() {
        return """
                <!DOCTYPE html>
                <html lang="ru">
                <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f8;padding:24px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:8px;overflow:hidden;">
                          <tr>
                            <td style="background-color:#0b6e99;padding:24px 32px;">
                              <span style="color:#ffffff;font-size:20px;font-weight:bold;letter-spacing:0.5px;">Water Tours</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px;color:#1f2933;font-size:15px;line-height:1.6;">
                              <p style="margin:0 0 16px;">Здравствуйте!</p>
                              <p style="margin:0 0 16px;">Спасибо за покупку билетов Water Tours — оплата прошла успешно.</p>
                              <p style="margin:0 0 20px;">Ваши билеты — во вложении к этому письму (PDF с QR-кодом на каждый билет).</p>
                              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f0f7fb;border-radius:6px;margin:0 0 20px;">
                                <tr>
                                  <td style="padding:16px 20px;color:#1f2933;font-size:14px;line-height:1.6;">
                                    <strong>Перед посадкой:</strong> покажите QR-код персоналу для прохода.
                                    Билет одноразовый, срок действия указан на самом билете.
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:0 0 4px;">Если у вас возникнут вопросы по заказу — просто ответьте на это письмо.</p>
                              <p style="margin:24px 0 0;">Хорошей прогулки!<br>Water Tours</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """;
    }
}
