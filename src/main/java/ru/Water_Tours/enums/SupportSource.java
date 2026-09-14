package ru.Water_Tours.enums;

/** Where a support inquiry came in from. Decides whether the bot can answer it at all. */
public enum SupportSource {
    /** Submitted through the question form on the public site. Reply contact is an e-mail or phone. */
    WEBSITE,
    /** Sent to the bot in a private chat. The originating chat id is the only reply channel. */
    TELEGRAM
}
