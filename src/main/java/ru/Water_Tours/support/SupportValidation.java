package ru.Water_Tours.support;

import ru.Water_Tours.enums.SupportContactKind;
import ru.Water_Tours.ticket.service.PhoneNormalizer;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Input rules shared by the web form and the bot, kept in one place so the two channels cannot
 * drift apart on what counts as an acceptable question.
 *
 * Everything is treated as plain text: control characters are stripped, the text is
 * length-bounded, and it is never interpreted as markup or as a bot command by the code that
 * stores it. The only places a question is rendered are an HTML-escaped staff page and a Telegram
 * message sent with no parse mode.
 */
public final class SupportValidation {

    public static final int MIN_MESSAGE_LENGTH = 10;

    /**
     * Intentionally permissive: this address is only ever read by a human who will answer it,
     * never used to look up an order or to send anything automatically.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private static final Pattern CONTROL_EXCEPT_NEWLINE = Pattern.compile("[\\p{Cntrl}&&[^\n]]");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

    private SupportValidation() {
    }

    /** Strips control characters, collapses runaway blank lines and trims. Never null. */
    public static String sanitizeText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String cleaned = CONTROL_EXCEPT_NEWLINE.matcher(raw).replaceAll(" ");
        cleaned = EXCESS_BLANK_LINES.matcher(cleaned).replaceAll("\n\n").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    public static boolean isAcceptableMessage(String sanitized) {
        return sanitized.length() >= MIN_MESSAGE_LENGTH && sanitized.length() <= SupportInquiry.MAX_MESSAGE_LENGTH;
    }

    /**
     * Classifies the reply contact the customer typed. An unrecognised value is refused rather
     * than stored as "something": an answer sent to an unparseable contact never arrives, and
     * saying so at submission time is more honest than accepting it.
     */
    public static Optional<Contact> parseContact(String raw) {
        String value = sanitizeText(raw, SupportInquiry.MAX_CONTACT_LENGTH);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.contains("@")) {
            return EMAIL.matcher(value).matches()
                    ? Optional.of(new Contact(SupportContactKind.EMAIL, value, value.toLowerCase()))
                    : Optional.empty();
        }
        return PhoneNormalizer.normalize(value)
                .map(normalized -> new Contact(SupportContactKind.PHONE, value, normalized));
    }

    /**
     * @param kind      how the customer wants to be reached
     * @param display   exactly what they typed, which is what the owner will see and dial
     * @param dedupeKey normalised form, used only as a rate-limit and duplicate key
     */
    public record Contact(SupportContactKind kind, String display, String dedupeKey) {
    }
}
