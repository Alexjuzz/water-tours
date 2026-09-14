package ru.Water_Tours.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.Water_Tours.support.SupportProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes the bot's command list once at startup, so «Задать вопрос» is a visible entry in the
 * client's command menu rather than something a customer has to know to type.
 *
 * Only the commands every customer may use are published. Staff commands stay unlisted: showing
 * /refund to everyone would advertise an action they cannot perform, and the authorisation that
 * actually matters is checked per update, not here.
 *
 * Runs on its own daemon thread and retries a few times, because the first call happens while the
 * container's network is still settling and a single transient failure would otherwise leave the
 * menu stale until the next restart. Startup is never delayed or blocked by it, and a run that
 * fails every attempt is logged and dropped - the menu is cosmetic, and both /question and the
 * reply-keyboard button work without it.
 */
@Component
public class TelegramCommandMenu implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandMenu.class);

    private static final long[] RETRY_DELAYS_MS = {0L, 5_000L, 20_000L};

    private final RestClient client;
    private final SupportProperties supportProperties;
    private final boolean botConfigured;

    public TelegramCommandMenu(@Qualifier("telegramRestClient") RestClient client,
                               SupportProperties supportProperties,
                               @Value("${telegram.bot-token:}") String botToken) {
        this.client = client;
        this.supportProperties = supportProperties;
        this.botConfigured = botToken != null && !botToken.isBlank();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!botConfigured) {
            return;
        }
        Thread publisher = new Thread(this::publishWithRetries, "telegram-command-menu");
        publisher.setDaemon(true);
        publisher.start();
    }

    private void publishWithRetries() {
        List<Map<String, String>> commands = new ArrayList<>();
        commands.add(Map.of("command", "tickets", "description", "Мои билеты"));
        if (supportProperties.isEnabled()) {
            commands.add(Map.of("command", "question", "description", "Задать вопрос"));
            commands.add(Map.of("command", "cancel", "description", "Отменить вопрос"));
        }

        String lastError = null;
        for (long delay : RETRY_DELAYS_MS) {
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                client.post().uri("/setMyCommands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("commands", commands, "scope", Map.of("type", "all_private_chats")))
                        .retrieve().toBodilessEntity();
                return;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName();
            }
        }
        log.warn("Telegram setMyCommands gave up after {} attempts, errorType={}; the command menu "
                + "keeps its previous contents", RETRY_DELAYS_MS.length, lastError);
    }
}
