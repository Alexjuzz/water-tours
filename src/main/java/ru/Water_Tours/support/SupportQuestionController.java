package ru.Water_Tours.support;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * The public question form's only endpoint.
 *
 * What it deliberately does NOT do: it never looks up an order, never confirms whether a phone or
 * e-mail belongs to a customer, and never returns anything about anyone's purchase. A stranger
 * submitting this form learns exactly one thing - the reference of the question they just wrote.
 * That keeps the form from becoming a way to probe the customer table.
 *
 * The Telegram bot token stays on the server: the browser talks to this endpoint, this endpoint
 * talks to Telegram.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportQuestionController {

    private static final Logger log = LoggerFactory.getLogger(SupportQuestionController.class);

    private final SupportService supportService;
    private final SupportRateLimiter rateLimiter;
    private final SupportProperties properties;

    public SupportQuestionController(SupportService supportService, SupportRateLimiter rateLimiter,
                                     SupportProperties properties) {
        this.supportService = supportService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Lets the site decide whether to show the button at all, so a visitor never meets a form
     * that cannot deliver anything. Exposes one boolean - no chat id, no token, no configuration.
     */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", properties.isEnabled());
    }

    @PostMapping("/questions")
    public ResponseEntity<?> submit(@RequestBody(required = false) QuestionRequest body, HttpServletRequest request) {
        if (!properties.isEnabled()) {
            // 503, not 400: the request is fine, the destination is not configured. Nothing is
            // stored, because nothing could ever be delivered from it.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("Форма вопросов сейчас недоступна. Попробуйте позже."));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(error("Пустой запрос."));
        }
        if (body.website() != null && !body.website().isBlank()) {
            // Honeypot: a field hidden from people and left empty by every real browser session.
            log.info("Support question rejected: honeypot filled");
            return ResponseEntity.badRequest().body(error("Не удалось отправить вопрос."));
        }

        String message = SupportValidation.sanitizeText(body.message(), SupportInquiry.MAX_MESSAGE_LENGTH);
        if (!SupportValidation.isAcceptableMessage(message)) {
            return ResponseEntity.badRequest().body(error(
                    "Опишите вопрос подробнее: от " + SupportValidation.MIN_MESSAGE_LENGTH
                            + " до " + SupportInquiry.MAX_MESSAGE_LENGTH + " символов."));
        }

        Optional<SupportValidation.Contact> contact = SupportValidation.parseContact(body.contact());
        if (contact.isEmpty()) {
            return ResponseEntity.badRequest().body(error(
                    "Укажите email или телефон целиком — иначе ответить будет некуда."));
        }

        String orderReference = SupportValidation.sanitizeText(body.orderReference(),
                SupportInquiry.MAX_ORDER_REFERENCE_LENGTH);

        SupportRateLimiter.Decision refusal = rateLimiter.check(contact.get().dedupeKey(), request.getRemoteAddr());
        if (refusal != null) {
            log.info("Support question rate limited, bucket={}", refusal);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "3600")
                    .body(error("Слишком много обращений подряд. Попробуйте позже."));
        }

        SupportInquiry inquiry = supportService.submitFromWebsite(message, contact.get(), orderReference);
        // Only reached after the transaction committed, so "accepted" means the question is
        // durably stored - not that it was delivered and certainly not that it was read.
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "reference", inquiry.getReference()));
    }

    private Map<String, Object> error(String message) {
        return Map.of("accepted", false, "error", message);
    }

    /**
     * @param website honeypot; any value means a bot filled a field people never see
     */
    public record QuestionRequest(String message, String contact, String orderReference, String website) {
    }
}
