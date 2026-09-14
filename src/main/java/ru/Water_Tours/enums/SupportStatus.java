package ru.Water_Tours.enums;

/**
 * Lifecycle of a support inquiry. NOTIFIED means the Telegram API accepted the notification for
 * the owner - never that the owner read it, and nothing shown to a customer claims otherwise.
 */
public enum SupportStatus {
    /** Durably stored, owner notification not accepted yet. */
    NEW,
    /** Telegram accepted the owner notification. */
    NOTIFIED,
    /** Retries exhausted: the owner was never notified. Visible at /staff/support-inquiries. */
    UNDELIVERED,
    /** The owner sent an answer with /reply. */
    ANSWERED
}
