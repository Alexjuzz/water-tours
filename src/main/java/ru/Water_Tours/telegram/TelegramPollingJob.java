package ru.Water_Tours.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-polls Telegram's getUpdates endpoint. No-ops without a configured bot token so local/CI
 * environments never make live calls. Offset is kept in memory only; a restart may reprocess a
 * small backlog, which is safe because linking and status lookups have no destructive side effects.
 */
@Component
public class TelegramPollingJob {
    private static final Logger log = LoggerFactory.getLogger(TelegramPollingJob.class);

    private final RestClient client;
    private final TelegramUpdateHandler handler;
    private final String botToken;
    private final AtomicLong offset = new AtomicLong(0);

    public TelegramPollingJob(@Qualifier("telegramRestClient") RestClient client, TelegramUpdateHandler handler,
                               @Value("${telegram.bot-token:}") String botToken) {
        this.client = client;
        this.handler = handler;
        this.botToken = botToken;
    }

    @Scheduled(fixedDelayString = "${telegram.poll-interval:500}", initialDelayString = "${telegram.poll-interval:500}")
    public void poll() {
        if (botToken == null || botToken.isBlank()) {
            return;
        }
        JsonNode response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/getUpdates").queryParam("timeout", 25).queryParam("offset", offset.get()).build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Telegram polling failed, errorType={}", e.getClass().getSimpleName());
            return;
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            return;
        }
        for (JsonNode update : response.path("result")) {
            offset.set(update.path("update_id").asLong() + 1);
            JsonNode message = update.path("message");
            if (message.isMissingNode()) {
                continue;
            }
            long chatId = message.path("chat").path("id").asLong();
            String text = message.path("text").asText(null);
            try {
                handler.handle(chatId, text);
            } catch (Exception e) {
                log.warn("Telegram update handling failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            }
        }
    }
}
