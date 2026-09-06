package ru.Water_Tours.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.TicketService;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class TelegramUpdateHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private static final String START_COMMAND = "/start";

    private final TelegramLinkService linkService;
    private final TicketService ticketService;
    private final TelegramSender sender;
    private final String baseUrl;

    public TelegramUpdateHandler(TelegramLinkService linkService, TicketService ticketService,
                                  TelegramSender sender, @Value("${app.base-url}") String baseUrl) {
        this.linkService = linkService;
        this.ticketService = ticketService;
        this.sender = sender;
        this.baseUrl = baseUrl;
    }

    public void handle(long chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.equals(START_COMMAND) || trimmed.startsWith(START_COMMAND + " ")) {
            handleStart(chatId, trimmed.substring(START_COMMAND.length()).trim());
        } else {
            handleStatus(chatId);
        }
    }

    private void handleStart(long chatId, String payload) {
        if (payload.isEmpty()) {
            sender.sendMessage(chatId, "Откройте эту ссылку с сайта после покупки билета, чтобы привязать его к боту.");
            return;
        }
        try {
            linkService.linkChat(payload, chatId);
            sender.sendMessage(chatId, "Билет привязан. Отправьте /tickets, чтобы посмотреть его статус.");
        } catch (Exception e) {
            log.warn("Telegram link failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Ссылка недействительна или устарела.");
        }
    }

    private void handleStatus(long chatId) {
        Optional<Order> linked = linkService.findLinkedOrder(chatId);
        if (linked.isEmpty()) {
            sender.sendMessage(chatId, "Билет ещё не привязан. Откройте ссылку с сайта после покупки.");
            return;
        }
        Order order = linked.get();
        List<TicketResponse> tickets = ticketService.getTickets(order.getId());
        if (tickets.isEmpty()) {
            sender.sendMessage(chatId, "Билеты ещё не выпущены. Попробуйте позже.");
            return;
        }
        String summary = tickets.stream()
                .map(t -> t.ticketType() + ": " + statusText(t.ticketStatus()))
                .collect(Collectors.joining("\n"));
        String pdfUrl = baseUrl + "/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + order.getAccessToken();
        sender.sendMessage(chatId, summary + "\n\nСкачать билет: " + pdfUrl);
    }

    private String statusText(TicketStatus status) {
        return switch (status) {
            case ISSUED -> "действителен";
            case USED -> "использован";
            case EXPIRED -> "истёк";
        };
    }
}
