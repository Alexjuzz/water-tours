package ru.Water_Tours.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.support.SupportInquiry;
import ru.Water_Tours.support.SupportMessages;
import ru.Water_Tours.support.SupportProperties;
import ru.Water_Tours.support.SupportService;
import ru.Water_Tours.support.SupportValidation;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.QrService;
import ru.Water_Tours.ticket.service.RefundService;
import ru.Water_Tours.ticket.service.TicketService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TelegramUpdateHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private static final String START_COMMAND = "/start";
    private static final String REFUND_COMMAND = "/refund";
    private static final String QUESTION_COMMAND = "/question";
    private static final String CANCEL_COMMAND = "/cancel";
    private static final String TICKETS_COMMAND = "/tickets";
    private static final String REPLY_COMMAND = "/reply";
    /** Deep-link payload that opens the question flow: https://t.me/<bot>?start=question */
    private static final String QUESTION_START_PAYLOAD = "question";
    /** Label of the persistent reply-keyboard button. Pressing it just sends this text. */
    public static final String QUESTION_BUTTON = "❓ Задать вопрос";

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneOffset.UTC);

    private final TelegramLinkService linkService;
    private final TicketService ticketService;
    private final TelegramSender sender;
    private final StaffTelegramAuthorization staffAuthorization;
    private final QrService qrService;
    private final OrderRepository orderRepository;
    private final RefundService refundService;
    private final SupportService supportService;
    private final SupportProperties supportProperties;
    private final SupportChatStates supportChatStates;
    private final String baseUrl;

    public TelegramUpdateHandler(TelegramLinkService linkService, TicketService ticketService,
                                  TelegramSender sender, StaffTelegramAuthorization staffAuthorization,
                                  QrService qrService, OrderRepository orderRepository, RefundService refundService,
                                  SupportService supportService, SupportProperties supportProperties,
                                  SupportChatStates supportChatStates,
                                  @Value("${app.base-url}") String baseUrl) {
        this.linkService = linkService;
        this.ticketService = ticketService;
        this.sender = sender;
        this.staffAuthorization = staffAuthorization;
        this.qrService = qrService;
        this.orderRepository = orderRepository;
        this.refundService = refundService;
        this.supportService = supportService;
        this.supportProperties = supportProperties;
        this.supportChatStates = supportChatStates;
        this.baseUrl = baseUrl;
    }

    /**
     * Dispatch order is load-bearing.
     *
     * <ol>
     *   <li><b>An open question state wins over everything except /cancel.</b> Whatever the chat
     *       sends next is the question - it is stored as text and never dispatched. That is what
     *       keeps "как оформить /refund?" from becoming a refund, and keeps a staff member who is
     *       typing a question from accidentally redeeming a ticket code they pasted.</li>
     *   <li><b>Explicit commands, before any catch-all.</b> The historical behaviour was that any
     *       message from a staff chat is a ticket code; that catch-all now runs last, so adding a
     *       command can no longer be shadowed by it.</li>
     *   <li>The original catch-all, unchanged: staff redeem, everyone else gets ticket status.</li>
     * </ol>
     *
     * Authorisation is decided here from the chat id in the update envelope only - never from a
     * username, a display name or forwarded content, all of which the sender controls.
     */
    public void handle(long chatId, String text, boolean isPrivateChat) {
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();

        if (isPrivateChat && supportChatStates.isAwaitingQuestion(chatId)) {
            if (isCommand(trimmed, CANCEL_COMMAND)) {
                supportChatStates.clear(chatId);
                sender.sendMessage(chatId, "Вопрос отменён. Ничего не отправлено.");
            } else {
                captureQuestion(chatId, trimmed);
            }
            return;
        }

        if (isCommand(trimmed, START_COMMAND)) {
            handleStart(chatId, argumentOf(trimmed), isPrivateChat);
            return;
        }
        if (isCommand(trimmed, QUESTION_COMMAND) || QUESTION_BUTTON.equals(trimmed)) {
            startQuestion(chatId, isPrivateChat);
            return;
        }
        if (isCommand(trimmed, CANCEL_COMMAND)) {
            sender.sendMessage(chatId, "Отменять нечего.");
            return;
        }
        if (isCommand(trimmed, TICKETS_COMMAND)) {
            handleStatus(chatId);
            return;
        }
        if (isCommand(trimmed, REPLY_COMMAND)) {
            handleOwnerReply(chatId, argumentOf(trimmed), isPrivateChat);
            return;
        }
        if (isCommand(trimmed, REFUND_COMMAND) && staffAuthorization.isStaff(chatId)) {
            if (!isPrivateChat) {
                sender.sendMessage(chatId, "Доступно только в личном чате с ботом.");
            } else {
                handleStaffRefund(chatId, argumentOf(trimmed));
            }
            return;
        }

        if (staffAuthorization.isStaff(chatId)) {
            if (!isPrivateChat) {
                sender.sendMessage(chatId, "Доступно только в личном чате с ботом.");
            } else {
                handleStaffRedeem(chatId, trimmed);
            }
        } else {
            handleStatus(chatId);
        }
    }

    private static boolean isCommand(String text, String command) {
        // Telegram appends @botname to a command sent in a group; accept both spellings.
        String head = text.split("\\s", 2)[0];
        int at = head.indexOf('@');
        if (at > 0) {
            head = head.substring(0, at);
        }
        return head.equalsIgnoreCase(command);
    }

    private static String argumentOf(String text) {
        String[] parts = text.split("\\s", 2);
        return parts.length > 1 ? parts[1].trim() : "";
    }

    // ---------------------------------------------------------------- customer support

    /**
     * Support lives in private chats only. A group is never a support channel: its members are
     * not the customer, and an answer sent there would show one person's contact to everyone.
     */
    private void startQuestion(long chatId, boolean isPrivateChat) {
        if (!isPrivateChat) {
            sender.sendMessage(chatId, "Задать вопрос можно только в личном чате с ботом.");
            return;
        }
        if (!supportProperties.isEnabled()) {
            sender.sendMessage(chatId, "Приём вопросов сейчас не настроен. Попробуйте позже.");
            return;
        }
        if (supportChatStates.isRateLimited(chatId)) {
            sender.sendMessage(chatId, "Вы уже отправили несколько вопросов. Дождитесь ответа на предыдущий.");
            return;
        }
        if (!supportChatStates.startQuestion(chatId)) {
            sender.sendMessage(chatId, "Сейчас слишком много обращений. Попробуйте через несколько минут.");
            return;
        }
        sender.sendMessage(chatId, "Напишите ваш вопрос одним сообщением (от "
                + SupportValidation.MIN_MESSAGE_LENGTH + " символов). "
                + "Если знаете номер заказа — укажите его в тексте. "
                + "Не отправляйте пароли и данные карты. Отменить — /cancel");
    }

    /** Stores whatever the chat sent while the question flow was open. Never dispatched. */
    private void captureQuestion(long chatId, String text) {
        String message = SupportValidation.sanitizeText(text, SupportInquiry.MAX_MESSAGE_LENGTH);
        if (!SupportValidation.isAcceptableMessage(message)) {
            sender.sendMessage(chatId, "Слишком коротко. Опишите вопрос от "
                    + SupportValidation.MIN_MESSAGE_LENGTH + " символов или отмените — /cancel");
            return;
        }
        SupportInquiry inquiry;
        try {
            inquiry = supportService.submitFromTelegram(chatId, message);
        } catch (Exception e) {
            log.warn("Support inquiry storage failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Не удалось сохранить вопрос. Попробуйте ещё раз чуть позже.");
            return;
        }
        supportChatStates.clear(chatId);
        supportChatStates.recordQuestion(chatId);
        // Said only after the inquiry is committed - and it claims storage, not that it was read.
        sender.sendMessage(chatId, SupportMessages.customerAcknowledgement(inquiry.getReference()));
    }

    /**
     * {@code /reply <reference> <text>} - the owner's answer.
     *
     * The recipient is read from the stored inquiry, never from the command: the owner supplies a
     * reference, not a chat id, so a forged or guessed argument can at worst address an inquiry
     * that exists, and the answer still goes only to the chat that actually asked it.
     */
    private void handleOwnerReply(long chatId, String argument, boolean isPrivateChat) {
        if (!supportProperties.isOwner(chatId) || !isPrivateChat) {
            sender.sendMessage(chatId, "Эта команда доступна только владельцу в личном чате.");
            return;
        }
        String[] parts = argument.split("\\s", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            sender.sendMessage(chatId, "Использование: /reply <номер обращения> <текст ответа>");
            return;
        }
        String reference = parts[0].trim().toUpperCase();
        String answer = SupportValidation.sanitizeText(parts[1], SupportInquiry.MAX_MESSAGE_LENGTH);
        if (answer.isEmpty()) {
            sender.sendMessage(chatId, "Пустой ответ не отправлен.");
            return;
        }

        Optional<SupportInquiry> found = supportService.findByReference(reference);
        if (found.isEmpty()) {
            sender.sendMessage(chatId, "Обращение " + reference + " не найдено.");
            return;
        }
        SupportInquiry inquiry = found.get();
        if (inquiry.getSource() != SupportSource.TELEGRAM || inquiry.getTelegramChatId() == null) {
            // Honest about the channel: the bot cannot dial a phone or send an e-mail, and saying
            // "sent" here would leave the owner believing a customer was answered.
            sender.sendMessage(chatId, "Обращение " + reference + " пришло с сайта — бот не может "
                    + "написать на почту или телефон. Контакт для ответа: " + inquiry.getContact());
            return;
        }

        TelegramSender.DeliveryResult result = sender.sendMessageChecked(inquiry.getTelegramChatId(),
                SupportMessages.answerToCustomer(reference, answer));
        if (result.accepted()) {
            supportService.markAnswered(inquiry.getId());
            log.info("Support answer relayed, reference={}", reference);
            sender.sendMessage(chatId, "Ответ на " + reference + " передан Telegram. "
                    + "Это подтверждение приёма, а не прочтения.");
        } else {
            log.warn("Support answer not delivered, reference={}, errorType={}", reference, result.errorType());
            sender.sendMessage(chatId, "Ответ на " + reference + " НЕ отправлен: Telegram отклонил сообщение ("
                    + result.errorType() + "). Возможно, клиент заблокировал бота.");
        }
    }

    // ---------------------------------------------------------------- existing flows

    private void handleStart(long chatId, String payload, boolean isPrivateChat) {
        if (QUESTION_START_PAYLOAD.equalsIgnoreCase(payload)) {
            startQuestion(chatId, isPrivateChat);
            return;
        }
        if (payload.isEmpty()) {
            String hint = "Откройте эту ссылку с сайта после покупки билета, чтобы привязать его к боту."
                    + (supportProperties.isEnabled() ? "\nЕсть вопрос? Отправьте /question" : "");
            if (supportProperties.isEnabled() && isPrivateChat) {
                sender.sendMessageWithButtons(chatId, hint, List.of(QUESTION_BUTTON));
            } else {
                sender.sendMessage(chatId, hint);
            }
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
            sender.sendMessage(chatId, "Билет ещё не привязан. Откройте ссылку с сайта после покупки."
                    + (supportProperties.isEnabled() ? "\nЕсть вопрос? Отправьте /question" : ""));
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
     *
     * <p>The image arrives as a supplier, not as bytes: every guard below runs before anything is
     * fetched. Polling is one scheduled loop, so downloading a stranger's 20 MB photo just to
     * refuse it stalled ticket status replies and question capture for everybody.
     */
    public void handlePhoto(long chatId, java.util.function.Supplier<byte[]> imageSource, boolean isPrivateChat) {
        if (isPrivateChat && supportChatStates.isAwaitingQuestion(chatId)) {
            // Mid-question a photo is not a ticket to redeem. Only text is accepted as a question,
            // so say so and leave the state open rather than falling through to redemption.
            sender.sendMessage(chatId, "Вопрос принимается только текстом. Напишите его сообщением или отмените — /cancel");
            return;
        }
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
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageSource.get()));
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
     * Mirrors CheckController.redeemForCheckPage's refusal mapping, case for case, so a Telegram
     * staff redeem carries the same one-time-use guarantee (same TicketService.redeemByCode
     * pessimistic lock) AND tells the staff member the same reason. It stopped mirroring it when
     * the refund hold was added: every refusal that was not "already used" came back here as
     * "expired or not yet valid", which is a false statement about a live order.
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
        } catch (ru.Water_Tours.ticket.service.RefundInProgressException e) {
            // Not "expired". A refund is deciding right now, and the person at the gate needs to
            // say that rather than send the customer away believing their ticket ran out.
            sender.sendMessage(chatId, "По этому заказу выполняется возврат. Посадка по билету недоступна.");
        } catch (ru.Water_Tours.ticket.service.OrderRefundedException e) {
            sender.sendMessage(chatId, "По этому заказу выполнен возврат. Билет недействителен.");
        } catch (IllegalArgumentException e) {
            sender.sendMessage(chatId, "Этот билет уже был использован.");
        } catch (IllegalStateException e) {
            sender.sendMessage(chatId, "Этот билет недействителен (истёк или ещё не начал действовать).");
        } catch (Exception e) {
            log.warn("Telegram staff redeem failed for chatId={}, errorType={}", chatId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Произошла ошибка при проверке билета.");
        }
    }

    /**
     * /refund <order UUID> executes the refund immediately (mirrors StaffRefundController).
     * /refund <email> only searches and lists matches - never refunds a guessed/first match,
     * so a mistyped or shared email can't trigger money movement without an explicit order ID.
     */
    private void handleStaffRefund(long chatId, String query) {
        if (query.isEmpty()) {
            sender.sendMessage(chatId, "Использование: /refund <email клиента или ID заказа>");
            return;
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(query);
        } catch (IllegalArgumentException notAUuid) {
            listOrdersForRefund(chatId, query);
            return;
        }
        try {
            RefundService.RefundResult result = refundService.refund(orderId);
            sender.sendMessage(chatId, "Возврат выполнен: " + result.amount() + " ₽, refundId=" + result.providerRefundId());
        } catch (IllegalStateException | NoSuchElementException e) {
            sender.sendMessage(chatId, "Возврат не выполнен: " + e.getMessage());
        } catch (ru.Water_Tours.ticket.service.PaymentProviderException e) {
            log.warn("Telegram staff refund unresolved for chatId={}, orderId={}, message={}", chatId, orderId, e.getMessage());
            boolean unknown = e.getMessage() != null && e.getMessage().contains("unknown");
            sender.sendMessage(chatId, unknown
                    ? "Результат возврата пока неизвестен. Билеты заблокированы, статус уточняется автоматически."
                    : "Возврат не выполнен: провайдер платежей отклонил запрос или недоступен.");
        } catch (Exception e) {
            log.warn("Telegram staff refund failed for chatId={}, orderId={}, errorType={}", chatId, orderId, e.getClass().getSimpleName());
            sender.sendMessage(chatId, "Возврат не выполнен: внутренняя ошибка. Проверьте статус заказа.");
        }
    }

    private void listOrdersForRefund(long chatId, String email) {
        List<Order> orders = orderRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(email);
        if (orders.isEmpty()) {
            sender.sendMessage(chatId, "Заказы с email " + email + " не найдены.");
            return;
        }
        String message = orders.stream().limit(10)
                .map(o -> ORDER_DATE.format(o.getCreatedAt()) + ", " + o.getTotalAmount() + " ₽, " + o.getStatus()
                        + "\nID: " + o.getId())
                .collect(Collectors.joining("\n\n"));
        sender.sendMessage(chatId, message + "\n\nЧтобы вернуть конкретный заказ, отправьте:\n/refund <ID заказа>");
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
            case REVOKED -> "возвращён (аннулирован)";
        };
    }
}
