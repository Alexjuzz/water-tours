package ru.Water_Tours.telegram;

public interface TelegramSender {

    /** Fire and forget. Failures are logged, not surfaced - used for informational replies. */
    void sendMessage(long chatId, String text);

    /**
     * Same send, but reports whether the Telegram API accepted the message. Needed wherever the
     * caller must be told the truth about delivery: the owner's {@code /reply} to a customer and
     * the retrying owner notification both depend on knowing an attempt failed.
     *
     * "Accepted" means the API took the message, never that anybody read it.
     *
     * The default keeps existing senders (and test doubles) working; the HTTP sender overrides it.
     */
    default DeliveryResult sendMessageChecked(long chatId, String text) {
        sendMessage(chatId, text);
        return DeliveryResult.ok();
    }

    /**
     * Sends a message that also shows a one-row reply keyboard - the always-visible «Задать
     * вопрос» button. The buttons only make the chat type that text; every decision is still
     * taken server-side from the update, so a button press grants nothing a typed message would
     * not.
     */
    default void sendMessageWithButtons(long chatId, String text, java.util.List<String> buttons) {
        sendMessage(chatId, text);
    }

    /**
     * @param accepted  whether the Telegram API took the message
     * @param errorType exception or API error class of a failure, never a message body or token
     */
    record DeliveryResult(boolean accepted, String errorType) {

        public static DeliveryResult ok() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult failed(String errorType) {
            return new DeliveryResult(false, errorType);
        }
    }
}
