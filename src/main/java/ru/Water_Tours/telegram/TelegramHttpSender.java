package ru.Water_Tours.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class TelegramHttpSender implements TelegramSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramHttpSender.class);

    private final RestClient client;

    public TelegramHttpSender(@Qualifier("telegramRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public void sendMessage(long chatId, String text) {
        try {
            client.post().uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Telegram sendMessage failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
        }
    }
}
