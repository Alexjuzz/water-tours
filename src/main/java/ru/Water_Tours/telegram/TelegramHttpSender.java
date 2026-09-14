package ru.Water_Tours.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TelegramHttpSender implements TelegramSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramHttpSender.class);

    private final RestClient client;
    private final boolean configured;

    public TelegramHttpSender(@Qualifier("telegramRestClient") RestClient client,
                              @Value("${telegram.bot-token:}") String botToken) {
        this.client = client;
        this.configured = botToken != null && !botToken.isBlank();
    }

    @Override
    public void sendMessage(long chatId, String text) {
        sendMessageChecked(chatId, text);
    }

    @Override
    public void sendMessageWithButtons(long chatId, String text, List<String> buttons) {
        send(chatId, text, Map.of(
                "keyboard", List.of(buttons.stream().map(label -> Map.of("text", label)).toList()),
                "resize_keyboard", true,
                "is_persistent", true));
    }

    @Override
    public DeliveryResult sendMessageChecked(long chatId, String text) {
        return send(chatId, text, null);
    }

    private DeliveryResult send(long chatId, String text, Map<String, Object> replyMarkup) {
        if (!configured) {
            // No token is a supported no-op (local and CI). Say so instead of reporting success:
            // a caller that shows "delivered" here would be lying about a message never attempted.
            return DeliveryResult.failed("BotTokenNotConfigured");
        }
        // No parse mode: a customer's question or an owner's answer is delivered as plain text
        // and can never be reinterpreted as markup by the receiving client.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        if (replyMarkup != null) {
            payload.put("reply_markup", replyMarkup);
        }
        try {
            JsonNode response = client.post().uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                // A 200 with ok=false is a real refusal (blocked bot, unknown chat). Only the
                // error code is kept: description can echo back the text we just tried to send.
                String errorCode = response == null ? "EmptyResponse" : "TelegramError" + response.path("error_code").asInt();
                log.warn("Telegram sendMessage refused for chatId={}, error={}", chatId, errorCode);
                return DeliveryResult.failed(errorCode);
            }
            return DeliveryResult.ok();
        } catch (Exception e) {
            log.warn("Telegram sendMessage failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            return DeliveryResult.failed(e.getClass().getSimpleName());
        }
    }
}
