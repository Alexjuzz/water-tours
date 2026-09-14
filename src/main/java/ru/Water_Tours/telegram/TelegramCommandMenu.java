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
 * A failure is logged and ignored - the menu is cosmetic and must never block startup.
 */
@Component
public class TelegramCommandMenu implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandMenu.class);

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
        List<Map<String, String>> commands = new java.util.ArrayList<>();
        commands.add(Map.of("command", "tickets", "description", "Мои билеты"));
        if (supportProperties.isEnabled()) {
            commands.add(Map.of("command", "question", "description", "Задать вопрос"));
            commands.add(Map.of("command", "cancel", "description", "Отменить вопрос"));
        }
        try {
            client.post().uri("/setMyCommands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("commands", commands, "scope", Map.of("type", "all_private_chats")))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Telegram setMyCommands failed, errorType={}", e.getClass().getSimpleName());
        }
    }
}
