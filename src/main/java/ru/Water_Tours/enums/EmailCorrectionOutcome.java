package ru.Water_Tours.enums;

/**
 * Outcome of a staff-made ticket e-mail address correction. ACCEPTED means the mail server took
 * the message (JavaMailSender returned without throwing) - it is never a claim about the inbox.
 */
public enum EmailCorrectionOutcome {
    PENDING,
    ACCEPTED,
    FAILED
}
