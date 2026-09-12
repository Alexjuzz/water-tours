package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketEmailService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Review screen for ticket e-mails that are queued but deliberately not delivered automatically.
 *
 * Automatic delivery only covers orders paid after the configured start date; everything older is
 * a backlog that built up while delivery was switched off, and its recipients have never been
 * reviewed. This page shows that backlog and sends one order at a time, on an explicit click.
 * There is no bulk action on purpose. Addresses are masked so the screen can be shared or
 * screenshotted without exposing customer contacts, and no access token is ever rendered.
 */
@Controller
@RequestMapping("/staff/mail-queue")
public class StaffMailQueueController {

    private static final Logger log = LoggerFactory.getLogger(StaffMailQueueController.class);

    private final OrderRepository orderRepository;
    private final TicketEmailService ticketEmailService;
    private final boolean deliveryEnabled;
    private final String deliverPaidFrom;

    public StaffMailQueueController(OrderRepository orderRepository, TicketEmailService ticketEmailService,
                                    @Value("${tickets.email-delivery.enabled:false}") boolean deliveryEnabled,
                                    @Value("${tickets.email-delivery.deliver-paid-from:}") String deliverPaidFrom) {
        this.orderRepository = orderRepository;
        this.ticketEmailService = ticketEmailService;
        this.deliveryEnabled = deliveryEnabled;
        this.deliverPaidFrom = deliverPaidFrom;
    }

    @GetMapping
    @ResponseBody
    public String page(@RequestParam(required = false) String msg,
                       @RequestParam(required = false) String error,
                       HttpServletRequest request) {
        Instant cutoff = cutoff();
        List<Order> queued = orderRepository.findOrdersAwaitingTicketEmail();

        StringBuilder sb = new StringBuilder();
        sb.append(StaffPages.open("Очередь писем", StaffPages.csrfToken(request)));
        sb.append("<h1>Очередь писем с билетами</h1>");
        if (msg != null) sb.append("<p style=\"color:#0a7d33\">").append(HtmlUtils.htmlEscape(msg)).append("</p>");
        if (error != null) sb.append("<p style=\"color:#b3261e\">").append(HtmlUtils.htmlEscape(error)).append("</p>");

        sb.append("<p>Автоматическая отправка: <strong>")
                .append(deliveryEnabled ? "включена" : "выключена").append("</strong>. ");
        if (cutoff != null) {
            sb.append("Автоматически уходят письма по заказам, оплаченным после <strong>")
                    .append(HtmlUtils.htmlEscape(cutoff.toString())).append("</strong>.");
        } else {
            sb.append("Дата начала не задана, поэтому автоматически не уходит ничего.");
        }
        sb.append("</p>");
        sb.append("<p class=\"muted\">Заказы ниже оплачены, билеты выпущены, письмо не отправлено. Более старые, "
                + "чем дата начала, удерживаются намеренно: их адреса никто не проверял. Отправка — по одному, "
                + "вручную. Адреса скрыты, токены доступа на странице не показываются.</p>");

        if (queued.isEmpty()) {
            sb.append("<p class=\"muted\">Очередь пуста.</p>");
        } else {
            sb.append("<table><tr><th>Оплачен</th><th>Заказ</th><th>Тип</th><th>Адрес</th><th>Сумма</th><th>Состояние</th><th>Действие</th></tr>");
            for (Order order : queued) {
                boolean auto = cutoff != null && order.getPaidAt() != null && order.getPaidAt().isAfter(cutoff);
                sb.append("<tr>");
                sb.append("<td>").append(order.getPaidAt()).append("</td>");
                sb.append("<td>");
                if (Boolean.TRUE.equals(order.getTestPaid())) {
                    sb.append("<span class=\"test-badge\">ТЕСТ</span> ");
                }
                sb.append(order.getId()).append("</td>");
                sb.append("<td>").append(order.getOrderType() == OrderType.PRIVATE_BOAT ? "катер" : "билеты").append("</td>");
                sb.append("<td>").append(HtmlUtils.htmlEscape(maskEmail(order.getEmail()))).append("</td>");
                sb.append("<td>").append(order.getTotalAmount()).append(" ₽</td>");
                sb.append("<td>").append(auto ? "уйдёт автоматически" : "<strong>удержано</strong>").append("</td>");
                sb.append("<td><form method=\"post\" action=\"/staff/mail-queue/").append(order.getId())
                        .append("/send\" onsubmit=\"return confirm('Отправить письмо по этому заказу?');\">");
                sb.append(StaffPages.csrfField(StaffPages.csrfToken(request)));
                sb.append("<button type=\"submit\">Отправить</button></form></td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append(StaffPages.close());
        return sb.toString();
    }

    @PostMapping("/{orderId}/send")
    public String send(@PathVariable UUID orderId, Authentication authentication) {
        try {
            ticketEmailService.sendTicketsPdf(orderId);
            log.info("Staff released a held ticket email: orderId={}, by={}", orderId,
                    authentication != null ? authentication.getName() : "unknown");
            return redirect("msg", "Письмо отправлено по заказу " + orderId);
        } catch (Exception e) {
            log.warn("Held ticket email failed for orderId={}, errorType={}", orderId, e.getClass().getSimpleName());
            return redirect("error", "Не удалось отправить письмо: " + e.getMessage());
        }
    }

    private Instant cutoff() {
        if (deliverPaidFrom == null || deliverPaidFrom.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(deliverPaidFrom.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** j***@y***.ru - enough to recognise an address, not enough to harvest one. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "—";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        String local = email.charAt(0) + "***";
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        if (dot < 1) {
            return local + "@" + domain.charAt(0) + "***";
        }
        return local + "@" + domain.charAt(0) + "***" + domain.substring(dot);
    }

    private String redirect(String param, String message) {
        return "redirect:/staff/mail-queue?" + param + "=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }
}
