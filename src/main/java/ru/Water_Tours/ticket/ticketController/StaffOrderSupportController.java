package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderEmailCorrection;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderEmailCorrectionRepository;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;
import ru.Water_Tours.ticket.service.PhoneNormalizer;
import ru.Water_Tours.ticket.service.StaffEmailCorrectionService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff console for answering "I paid and got nothing" phone calls.
 *
 * The system stores no customer name, so a caller is identified by exactly three things: the
 * order id, the e-mail on the order, or the phone on the order. All three are matched exactly -
 * a partial value returns nothing rather than a list to browse, so this page cannot be used to
 * enumerate customers.
 *
 * It is separate from {@code /staff/refund} on purpose: that page moves money, this one only
 * explains an order and, in one narrow case, corrects where the ticket is sent.
 */
@Controller
@RequestMapping("/staff/order-support")
public class StaffOrderSupportController {

    private static final Logger log = LoggerFactory.getLogger(StaffOrderSupportController.class);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final OrderEmailCorrectionRepository correctionRepository;
    private final StaffEmailCorrectionService correctionService;

    public StaffOrderSupportController(OrderRepository orderRepository,
                                       TicketRepository ticketRepository,
                                       OrderEmailCorrectionRepository correctionRepository,
                                       StaffEmailCorrectionService correctionService) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.correctionRepository = correctionRepository;
        this.correctionService = correctionService;
    }

    @GetMapping
    @ResponseBody
    public String page(@RequestParam(required = false) String query,
                       @RequestParam(required = false) String msg,
                       @RequestParam(required = false) String warn,
                       @RequestParam(required = false) String error,
                       HttpServletRequest request) {
        CsrfToken csrfToken = StaffPages.csrfToken(request);
        StringBuilder sb = new StringBuilder();
        sb.append(StaffPages.open("Поддержка заказа", csrfToken));
        sb.append("<h1>Поддержка заказа</h1>");

        if (msg != null) sb.append("<p style=\"color:#0a7d33\">").append(HtmlUtils.htmlEscape(msg)).append("</p>");
        if (warn != null) sb.append("<p style=\"color:#8a4b00\">").append(HtmlUtils.htmlEscape(warn)).append("</p>");
        if (error != null) sb.append("<p style=\"color:#b3261e\">").append(HtmlUtils.htmlEscape(error)).append("</p>");

        sb.append("<p class=\"muted\">Имя покупателя система не хранит и никогда не хранила. Клиента определяют "
                + "только три значения: номер заказа, email из заказа и телефон из заказа. Поиск точный: неполное "
                + "значение ничего не находит — списка клиентов здесь нет. Если звонящий не может назвать ни одного "
                + "из трёх значений, опознать заказ нечем; не угадывайте «кто это».</p>");

        sb.append("<form method=\"get\" action=\"/staff/order-support\">");
        sb.append("<input type=\"text\" name=\"query\" size=\"46\" autocomplete=\"off\" "
                + "placeholder=\"ID заказа, email или телефон целиком\" value=\"")
                .append(HtmlUtils.htmlEscape(query != null ? query : "")).append("\">");
        sb.append(" <button type=\"submit\">Найти</button>");
        sb.append("</form>");

        if (query != null && !query.isBlank()) {
            Lookup lookup = find(query.trim());
            if (lookup.rejected() != null) {
                sb.append("<p style=\"color:#8a4b00\">").append(HtmlUtils.htmlEscape(lookup.rejected())).append("</p>");
            } else if (lookup.orders().isEmpty()) {
                sb.append("<p>Ничего не найдено. Проверьте, что значение введено целиком и без опечаток.</p>");
            } else {
                for (Order order : lookup.orders()) {
                    appendOrder(sb, order, query.trim(), csrfToken);
                }
            }
        }

        sb.append(CONFIRM_SCRIPT);
        sb.append(StaffPages.close());
        return sb.toString();
    }

    @PostMapping("/{orderId}/email")
    public String correctEmail(@PathVariable UUID orderId,
                               @RequestParam(required = false) String newEmail,
                               @RequestParam(required = false) String confirmEmail,
                               @RequestParam(required = false) String reason,
                               @RequestParam(required = false) String query,
                               Authentication authentication) {
        String staff = authentication != null ? authentication.getName() : null;
        try {
            StaffEmailCorrectionService.Result result =
                    correctionService.correctAndResend(orderId, newEmail, confirmEmail, reason, staff);
            return redirect(result.delivered() ? "msg" : "warn", result.message(), query);
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            log.warn("Staff email correction rejected for orderId={}, by={}, reason={}", orderId, staff, e.getMessage());
            return redirect("error", e.getMessage(), query);
        } catch (Exception e) {
            log.warn("Staff email correction failed for orderId={}, by={}, errorType={}", orderId, staff,
                    e.getClass().getSimpleName());
            return redirect("error", "Исправление адреса не выполнено: внутренняя ошибка. "
                    + "Проверьте состояние заказа перед повторной попыткой.", query);
        }
    }

    // ------------------------------------------------------------------ search

    /** Either the orders found, or the reason the input was not accepted as a whole identifier. */
    private record Lookup(List<Order> orders, String rejected) {
        static Lookup of(List<Order> orders) {
            return new Lookup(orders, null);
        }

        static Lookup reject(String reason) {
            return new Lookup(List.of(), reason);
        }
    }

    private Lookup find(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            return Lookup.of(orderRepository.findById(id).map(List::of).orElse(List.of()));
        } catch (IllegalArgumentException notAUuid) {
            // not an order id - fall through
        }
        if (raw.contains("@")) {
            return Lookup.of(orderRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(
                    raw.trim().toLowerCase(Locale.ROOT)));
        }
        Optional<String> phone = PhoneNormalizer.normalize(raw);
        if (phone.isPresent()) {
            return Lookup.of(orderRepository.findAllByNormalizedPhone(PhoneNormalizer.storedVariants(phone.get())));
        }
        return Lookup.reject("Не похоже ни на номер заказа, ни на email, ни на телефон целиком. "
                + "Введите полный UUID заказа, полный email или полный номер телефона.");
    }

    // ----------------------------------------------------------------- rendering

    private void appendOrder(StringBuilder sb, Order order, String query, CsrfToken csrfToken) {
        List<Ticket> tickets = ticketRepository.findAllByOrderId(order.getId());

        sb.append("<h2>Заказ ");
        if (Boolean.TRUE.equals(order.getTestPaid())) {
            sb.append("<span class=\"test-badge\">ТЕСТ</span> ");
        }
        sb.append(HtmlUtils.htmlEscape(order.getId().toString())).append("</h2>");

        sb.append("<table>");
        row(sb, "Тип", order.getOrderType() == OrderType.PRIVATE_BOAT ? "аренда катера" : "билеты");
        if (order.getOrderType() == OrderType.PRIVATE_BOAT) {
            row(sb, "Аренда", text(order.getBoatDurationMinutes()) + " мин, гостей: " + text(order.getBoatGuestCount())
                    + ", маршрут: " + text(order.getBoatRouteType()));
        }
        row(sb, "Email в заказе", text(order.getEmail()));
        row(sb, "Телефон в заказе", text(order.getPhone()));
        row(sb, "Сумма", order.getTotalAmount() + " ₽");
        row(sb, "Статус заказа", text(order.getStatus()));
        row(sb, "Создан", text(order.getCreatedAt()));
        row(sb, "Оплачен", text(order.getPaidAt()));
        row(sb, "Билеты выпущены", text(order.getTicketIssuedAt()));
        row(sb, "Письмо принято SMTP", order.getTicketsEmailedAt() != null
                ? HtmlUtils.htmlEscape(order.getTicketsEmailedAt().toString()) + " — почтовый сервер принял письмо; "
                  + "это не подтверждение, что клиент его увидел"
                : "нет — письмо по текущему адресу ещё не принято");
        row(sb, "Последняя попытка отправки", text(order.getTicketsEmailAttemptAt()));
        row(sb, "Повторных отправок клиентом", order.getTicketsEmailAttempts() == null
                ? "0" : String.valueOf(order.getTicketsEmailAttempts()));
        row(sb, "Отправка идёт прямо сейчас", order.getTicketsEmailClaimedAt() != null ? "да" : "нет");
        if (order.getRefundPendingAt() != null) {
            row(sb, "Возврат", "выполняется с " + HtmlUtils.htmlEscape(order.getRefundPendingAt().toString()));
        }
        sb.append("</table>");

        sb.append("<h3>Билеты</h3>");
        if (tickets.isEmpty()) {
            sb.append("<p class=\"muted\">Билетов по заказу нет.</p>");
        } else {
            sb.append("<table><tr><th>Тип</th><th>Статус</th><th>Действует с</th><th>Действует до</th><th>Погашен</th></tr>");
            for (Ticket ticket : tickets) {
                sb.append("<tr><td>").append(text(ticket.getTicketType())).append("</td>");
                sb.append("<td>").append(text(ticket.getTicketStatus())).append("</td>");
                sb.append("<td>").append(text(ticket.getValidFrom())).append("</td>");
                sb.append("<td>").append(text(ticket.getValidTo())).append("</td>");
                sb.append("<td>").append(text(ticket.getUsedAt())).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("<p class=\"muted\">Коды билетов и ссылки доступа здесь не показываются.</p>");
        }

        appendCorrectionHistory(sb, order.getId());
        appendCorrectionForm(sb, order, tickets, query, csrfToken);
    }

    private void appendCorrectionHistory(StringBuilder sb, UUID orderId) {
        List<OrderEmailCorrection> history = correctionRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId);
        if (history.isEmpty()) {
            return;
        }
        sb.append("<h3>Исправления адреса</h3>");
        sb.append("<table><tr><th>Когда</th><th>Было</th><th>Стало</th><th>Кто</th><th>Причина</th><th>Итог</th></tr>");
        for (OrderEmailCorrection correction : history) {
            sb.append("<tr><td>").append(text(correction.getCreatedAt())).append("</td>");
            sb.append("<td>").append(HtmlUtils.htmlEscape(StaffMailQueueController.maskEmail(correction.getOldEmail()))).append("</td>");
            sb.append("<td>").append(HtmlUtils.htmlEscape(StaffMailQueueController.maskEmail(correction.getNewEmail()))).append("</td>");
            sb.append("<td>").append(text(correction.getStaffPrincipal())).append("</td>");
            sb.append("<td>").append(text(correction.getReason())).append("</td>");
            sb.append("<td>").append(outcomeText(correction)).append("</td></tr>");
        }
        sb.append("</table>");
    }

    private String outcomeText(OrderEmailCorrection correction) {
        return switch (correction.getOutcome()) {
            case ACCEPTED -> "принято SMTP";
            case FAILED -> "не отправлено" + (correction.getFailureType() != null
                    ? " (" + HtmlUtils.htmlEscape(correction.getFailureType()) + ")" : "");
            case PENDING -> "результат неизвестен";
        };
    }

    private void appendCorrectionForm(StringBuilder sb, Order order, List<Ticket> tickets,
                                      String query, CsrfToken csrfToken) {
        sb.append("<h3>Исправить адрес доставки билета</h3>");
        Optional<String> blocked = correctionService.ineligibilityReason(order, tickets);
        if (blocked.isPresent()) {
            sb.append("<p style=\"color:#8a4b00\">Недоступно: ").append(HtmlUtils.htmlEscape(blocked.get())).append("</p>");
            return;
        }

        sb.append("<p class=\"muted\">Отправляется уже выпущенный билет по этому заказу. Новый билет не создаётся, "
                + "оплата не затрагивается, QR-код остаётся прежним. Адрес в заказе меняется только после проверки. "
                + "Не более ").append(correctionService.maxPerOrder()).append(" исправлений на заказ, интервал ")
                .append(correctionService.cooldown().getSeconds()).append(" с.</p>");

        sb.append("<form method=\"post\" action=\"/staff/order-support/").append(order.getId()).append("/email\" ")
                .append("data-old-masked=\"")
                .append(HtmlUtils.htmlEscape(StaffMailQueueController.maskEmail(order.getEmail())))
                .append("\" onsubmit=\"return wtConfirmCorrection(this)\">");
        sb.append(StaffPages.csrfField(csrfToken));
        sb.append("<input type=\"hidden\" name=\"query\" value=\"").append(HtmlUtils.htmlEscape(query)).append("\">");
        sb.append("<p><label>Новый email<br><input type=\"email\" name=\"newEmail\" size=\"40\" required "
                + "autocomplete=\"off\"></label></p>");
        sb.append("<p><label>Новый email ещё раз<br><input type=\"email\" name=\"confirmEmail\" size=\"40\" required "
                + "autocomplete=\"off\"></label></p>");
        sb.append("<p><label>Причина (обязательно, до 300 символов)<br><input type=\"text\" name=\"reason\" size=\"60\" "
                + "maxlength=\"300\" required autocomplete=\"off\" placeholder=\"клиент назвал опечатку в адресе\">"
                + "</label></p>");
        sb.append("<button type=\"submit\">Исправить и отправить билет</button>");
        sb.append("</form>");
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th>").append(HtmlUtils.htmlEscape(label)).append("</th><td>").append(value).append("</td></tr>");
    }

    private static String text(Object value) {
        if (value == null) return "—";
        String asText = value instanceof Instant instant ? instant.toString() : String.valueOf(value);
        return asText.isBlank() ? "—" : HtmlUtils.htmlEscape(asText);
    }

    private String redirect(String param, String message, String query) {
        StringBuilder url = new StringBuilder("redirect:/staff/order-support?");
        if (query != null && !query.isBlank()) {
            url.append("query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8)).append('&');
        }
        url.append(param).append('=').append(URLEncoder.encode(
                message == null ? "Готово." : message, StandardCharsets.UTF_8));
        return url.toString();
    }

    /**
     * The browser confirmation names only masked addresses, so a screen share or a screenshot of
     * the confirmation dialog cannot leak a customer contact. It also blocks the second press: the
     * submit button is disabled once the form is on its way, which together with the delivery
     * claim on the server keeps one correction from being sent twice.
     */
    private static final String CONFIRM_SCRIPT = "<script>\n"
            + "function wtMaskEmail(value) {\n"
            + "  value = String(value || '').trim();\n"
            + "  var at = value.indexOf('@');\n"
            + "  if (at < 1) return '***';\n"
            + "  var domain = value.slice(at + 1);\n"
            + "  var dot = domain.lastIndexOf('.');\n"
            + "  return value.charAt(0) + '***@' + domain.charAt(0) + '***' + (dot > 0 ? domain.slice(dot) : '');\n"
            + "}\n"
            + "function wtConfirmCorrection(form) {\n"
            + "  var next = form.elements['newEmail'].value.trim().toLowerCase();\n"
            + "  var again = form.elements['confirmEmail'].value.trim().toLowerCase();\n"
            + "  if (!next || next !== again) { alert('Адреса не совпадают. Введите новый email дважды.'); return false; }\n"
            + "  if (!form.elements['reason'].value.trim()) { alert('Укажите причину исправления.'); return false; }\n"
            + "  var was = form.getAttribute('data-old-masked') || '***';\n"
            + "  if (!confirm('Отправить уже выпущенный билет на другой адрес?\\n\\nБыло: ' + was\n"
            + "      + '\\nСтанет: ' + wtMaskEmail(next) + '\\n\\nНовый билет не создаётся, оплата не меняется.')) return false;\n"
            + "  var button = form.querySelector('button[type=submit]');\n"
            + "  if (button) setTimeout(function () { button.disabled = true; button.textContent = 'Отправляем...'; }, 0);\n"
            + "  return true;\n"
            + "}\n"
            + "</script>";
}
