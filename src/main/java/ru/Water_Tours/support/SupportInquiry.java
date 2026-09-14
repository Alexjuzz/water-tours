package ru.Water_Tours.support;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.Water_Tours.enums.SupportContactKind;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.SupportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer question, stored before anything is sent anywhere.
 *
 * The public handle is {@code reference}, a random string: it is what the customer is shown and
 * what the owner types into {@code /reply}. The database id is never exposed, so a reference
 * cannot be guessed from another one and knowing a reference reveals nothing else.
 *
 * Deliberately minimal personal data: the reply contact the customer typed, the question text,
 * and - for a Telegram inquiry - the private chat id that asked. No name, no IP, no order lookup.
 * {@code orderReference} is free text the customer optionally typed; it is never resolved against
 * the order table, so a stranger cannot use this form to learn anything about someone's order.
 *
 * None of these fields is ever written to the application log.
 */
@Entity
@Getter
@Setter
@Table(name = "support_inquiries",
        indexes = {
                @Index(name = "idx_support_inquiries_reference", columnList = "reference", unique = true),
                @Index(name = "idx_support_inquiries_status", columnList = "status, created_at")
        })
public class SupportInquiry {

    /** Longest question accepted from either channel. Also the column width. */
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_CONTACT_LENGTH = 160;
    public static final int MAX_ORDER_REFERENCE_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Public handle shown to the customer and used by the owner's /reply. Its uniqueness is
     * declared once, as the unique index on the table above, so Hibernate and the migration
     * agree on exactly one constraint rather than each adding its own.
     */
    @Column(name = "reference", nullable = false, length = 20)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private SupportSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportStatus status;

    @Column(name = "message", nullable = false, length = MAX_MESSAGE_LENGTH)
    private String message;

    /** E-mail or phone for a website inquiry; null for Telegram, where the chat is the channel. */
    @Column(name = "contact", length = MAX_CONTACT_LENGTH)
    private String contact;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_kind", nullable = false, length = 20)
    private SupportContactKind contactKind;

    /** Whatever the customer typed as "order number". Never matched against a real order. */
    @Column(name = "order_reference", length = MAX_ORDER_REFERENCE_LENGTH)
    private String orderReference;

    /**
     * The private chat that sent a Telegram inquiry. This is the ONLY address /reply can send to -
     * the owner cannot supply a recipient, so a forged reference cannot redirect an answer.
     */
    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    /** Fingerprint of contact+message, used to collapse a double submit into one inquiry. */
    @Column(name = "dedupe_hash", length = 64)
    private String dedupeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When Telegram accepted the owner notification. Null while it has not. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "notify_attempts", nullable = false)
    private int notifyAttempts;

    @Column(name = "next_notify_at")
    private Instant nextNotifyAt;

    /** Exception type of the last failed notification. Never a message body or a token. */
    @Column(name = "last_notify_error", length = 120)
    private String lastNotifyError;

    @Column(name = "answered_at")
    private Instant answeredAt;
}
