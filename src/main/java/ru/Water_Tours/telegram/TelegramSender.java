package ru.Water_Tours.telegram;

public interface TelegramSender {
    void sendMessage(long chatId, String text);
}
