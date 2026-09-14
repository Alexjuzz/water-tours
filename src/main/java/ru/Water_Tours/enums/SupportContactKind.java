package ru.Water_Tours.enums;

/** How the customer asked to be reached back. */
public enum SupportContactKind {
    EMAIL,
    PHONE,
    /** The inquiry arrived in a Telegram private chat; the reply goes back to that same chat. */
    TELEGRAM
}
