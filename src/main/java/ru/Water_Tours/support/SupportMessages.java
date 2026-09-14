package ru.Water_Tours.support;

import ru.Water_Tours.enums.SupportSource;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Plain-text bodies for every support message the bot sends.
 *
 * Everything here is sent WITHOUT a Telegram parse mode, so a question containing asterisks,
 * underscores or angle brackets is delivered as the customer typed it and cannot turn into
 * markup - or into anything the owner's client would render as a link or a command.
 */
public final class SupportMessages {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(MOSCOW);

    private SupportMessages() {
    }

    /** What the owner receives for one inquiry. Contains the reply contact, by design. */
    public static String ownerNotification(SupportInquiry inquiry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Новый вопрос ").append(inquiry.getReference()).append('\n');
        sb.append("Источник: ").append(inquiry.getSource() == SupportSource.WEBSITE ? "сайт" : "Telegram").append('\n');
        sb.append("Время: ").append(STAMP.format(inquiry.getCreatedAt())).append(" МСК\n");
        if (inquiry.getOrderReference() != null) {
            sb.append("Номер заказа со слов клиента: ").append(inquiry.getOrderReference()).append('\n');
        }
        if (inquiry.getSource() == SupportSource.WEBSITE) {
            sb.append("Контакт для ответа: ").append(inquiry.getContact()).append('\n');
            sb.append("Это вопрос с сайта: бот не может написать на почту или телефон. "
                    + "Ответьте по этому контакту сами.\n");
        } else {
            sb.append("Ответить в Telegram: /reply ").append(inquiry.getReference()).append(" <текст>\n");
        }
        sb.append('\n').append(inquiry.getMessage());
        return sb.toString();
    }

    /** What the customer sees in Telegram once the question is stored. */
    public static String customerAcknowledgement(String reference) {
        return "Вопрос сохранён, номер обращения " + reference + ". "
                + "Мы передали его в поддержку. Это не значит, что его уже прочитали — "
                + "ответ придёт в этот чат.";
    }

    /** Prefix on the owner's answer, so the customer knows which question it belongs to. */
    public static String answerToCustomer(String reference, String text) {
        return "Ответ поддержки на обращение " + reference + ":\n\n" + text;
    }
}
