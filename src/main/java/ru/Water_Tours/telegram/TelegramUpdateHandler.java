package ru.Water_Tours.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.QrService;
import ru.Water_Tours.ticket.service.TicketService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Component
public class TelegramUpdateHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private static final String START_COMMAND = "/start";
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneOffset.UTC);

    private final TelegramLinkService linkService;
    private final TicketService ticketService;
    private final TelegramSender sender;
    private final StaffTelegramAuthorization staffAuthorization;
    private final QrService qrService;
    private final String baseUrl;

    public TelegramUpdateHandler(TelegramLinkService linkService, TicketService ticketService,
                                  TelegramSender sender, StaffTelegramAuthorization staffAuthorization,
                                  QrService qrService, @Value("${app.base-url}") String baseUrl) {
        this.linkService = linkService;
        this.ticketService = ticketService;
        this.sender = sender;
        this.staffAuthorization = staffAuthorization;
        this.qrService = qrService;
        this.baseUrl = baseUrl;
    }

    public void handle(long chatId, String text, boolean isPrivateChat) {
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.equals(START_COMMAND) || trimmed.startsWith(START_COMMAND + " ")) {
            handleStart(chatId, trimmed.substring(START_COMMAND.length()).trim());
        } else if (staffAuthorization.isStaff(chatId)) {
            if (isPrivateChat) {
                handleStaffRedeem(chatId, trimmed);
            } else {
                sender.sendMessage(chatId, "Проверка билетов доступна только в личном чате с ботом.");
            }
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
        List<Order> orders = linkService.findLinkedOrders(chatId);
        if (orders.isEmpty()) {
            sender.sendMessage(chatId, "Билет ещё не привязан. Откройте ссылку с сайта после покупки.");
            return;
        }
        String message = orders.stream()
                .map(this::describeOrder)
                .collect(Collectors.joining("\n\n"));
        sender.sendMessage(chatId, message);
    }

    private String describeOrder(Order order) {
        String label = "Заказ от " + ORDER_DATE.format(order.getCreatedAt()) + ":";
        List<TicketResponse> tickets = ticketService.getTickets(order.getId());
        if (tickets.isEmpty()) {
            return label + " билеты ещё не выпущены.";
        }
        String summary = tickets.stream()
                .map(t -> t.ticketType() + ": " + statusText(t.ticketStatus()))
                .collect(Collectors.joining("\n"));
        String pdfUrl = baseUrl + "/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + order.getAccessToken();
        return label + "\n" + summary + "\nСкачать билет: " + pdfUrl;
    }

    /**
     * Staff can send a photo of the printed/screen QR instead of typing the code. Decodes with
     * the same ZXing pipeline used to generate the ticket's QR, then reuses handleStaffRedeem.
     */
    public void handlePhoto(long chatId, byte[] imageBytes, boolean isPrivateChat) {
        if (!staffAuthorization.isStaff(chatId)) {
            sender.sendMessage(chatId, "Фото билета принимает только персонал.");
            return;
        }
        if (!isPrivateChat) {
            sender.sendMessage(chatId, "Проверка билетов доступна только в личном чате с ботом.");
            return;
        }
        String decoded;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new IllegalArgumentException("Not a readable image");
            }
            decoded = qrService.decodeQRCode(image);
        } catch (Exception e) {
            log.warn("Telegram QR decode failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Не удалось распознать QR-код на фото.");
            return;
        }
        handleStaffRedeem(chatId, decoded);
    }

    /**
     * Mirrors CheckController.redeemForCheckPage's exception mapping so a Telegram staff redeem
     * carries the same one-time-use guarantee (same TicketService.redeemByCode pessimistic lock).
     */
    private void handleStaffRedeem(long chatId, String text) {
        String code = extractTicketCode(text);
        if (code.isEmpty() || !code.matches("^[A-Za-z0-9-]{1,64}$")) {
            sender.sendMessage(chatId, "Не удалось распознать код билета. Пришлите код или ссылку из QR-кода.");
            return;
        }
        try {
            ticketService.redeemByCode(code);
            sender.sendMessage(chatId, "Билет действителен. Погашён.");
        } catch (NoSuchElementException e) {
            sender.sendMessage(chatId, "Билет не найден.");
        } catch (IllegalArgumentException e) {
            sender.sendMessage(chatId, "Этот билет уже был использован.");
        } catch (IllegalStateException e) {
            sender.sendMessage(chatId, "Этот билет недействителен (истёк или ещё не начал действовать).");
        } catch (Exception e) {
            log.warn("Telegram staff redeem failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Произошла ошибка при проверке билета.");
        }
    }

    private String extractTicketCode(String text) {
        String candidate = text.trim();
        int slashIdx = candidate.lastIndexOf("/t/");
        if (slashIdx >= 0) {
            candidate = candidate.substring(slashIdx + "/t/".length());
        }
        int queryIdx = candidate.indexOf('?');
        if (queryIdx >= 0) {
            candidate = candidate.substring(0, queryIdx);
        }
        return candidate.trim();
    }

    private String statusText(TicketStatus status) {
        return switch (status) {
            case ISSUED -> "действителен";
            case USED -> "использован";
            case EXPIRED -> "истёк";
        };
    }
}
