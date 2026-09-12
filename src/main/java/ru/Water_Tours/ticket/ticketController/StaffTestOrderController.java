package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.PdfTicketService;
import ru.Water_Tours.ticket.service.StaffTestOrderService;
import ru.Water_Tours.ticket.service.TicketService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Staff-only rehearsal of the paid-ticket path. Everything here is reachable only under
 * /staff/** (STAFF role + CSRF); there is no public or token-based entry point, and the PDF is
 * served for test orders only so this page can never be used to pull a real customer's ticket.
 */
@Controller
@RequestMapping("/staff/test-order")
public class StaffTestOrderController {

    private static final Logger log = LoggerFactory.getLogger(StaffTestOrderController.class);

    private final StaffTestOrderService testOrders;
    private final TicketService ticketService;
    private final PdfTicketService pdfTicketService;
    private final String baseUrl;

    public StaffTestOrderController(StaffTestOrderService testOrders, TicketService ticketService,
                                    PdfTicketService pdfTicketService, @Value("${app.base-url}") String baseUrl) {
        this.testOrders = testOrders;
        this.ticketService = ticketService;
        this.pdfTicketService = pdfTicketService;
        this.baseUrl = baseUrl;
    }

    @GetMapping
    @ResponseBody
    public String page(@RequestParam(required = false) String msg,
                       @RequestParam(required = false) String error,
                       HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(StaffPages.open("Тестовый билет", StaffPages.csrfToken(request)));
        sb.append("<h1>Тестовый билет</h1>");
        if (msg != null) sb.append("<p style=\"color:#0a7d33\">").append(HtmlUtils.htmlEscape(msg)).append("</p>");
        if (error != null) sb.append("<p style=\"color:#b3261e\">").append(HtmlUtils.htmlEscape(error)).append("</p>");

        sb.append("<p>Создаёт помеченный тестовый заказ и сразу выпускает билет. Деньги не списываются, платёжный ")
                .append("провайдер не вызывается. Тестовые заказы <strong>никогда не попадают в автоматическую рассылку</strong> — ")
                .append("письмо по ним уходит только по кнопке «Отправить письмо» ниже, на тот адрес, который вы укажете.</p>");
        sb.append("<p class=\"muted\">Если оставить адрес пустым, заказ получит служебный адрес ")
                .append(HtmlUtils.htmlEscape(StaffTestOrderService.TEST_EMAIL_DOMAIN))
                .append(" — тогда письмо отправить нельзя, но выдачу и PDF проверить можно.</p>");

        sb.append("<form method=\"post\" action=\"/staff/test-order\">");
        sb.append(StaffPages.csrfField(StaffPages.csrfToken(request)));
        sb.append("<p><label>Тип заказа <select name=\"flow\">")
                .append("<option value=\"ticket\">обычный билет (взрослый, 1 шт.)</option>")
                .append("<option value=\"boat\">аренда катера (60 мин, 2 гостя)</option>")
                .append("</select></label></p>");
        sb.append("<p><label>Email для письма <input type=\"email\" name=\"email\" placeholder=\"можно оставить пустым\" size=\"32\"></label></p>");
        sb.append("<button type=\"submit\">Создать тестовый заказ и выдать билет</button>");
        sb.append("</form>");

        List<Order> orders = testOrders.recentTestOrders();
        sb.append("<h2>Тестовые заказы</h2>");
        if (orders.isEmpty()) {
            sb.append("<p class=\"muted\">Пока ни одного тестового заказа не создано.</p>");
        } else {
            sb.append("<table><tr><th>Создан</th><th>Заказ</th><th>Тип</th><th>Адрес</th><th>Билеты</th><th>PDF</th><th>Письмо</th></tr>");
            for (Order order : orders) {
                List<TicketResponse> tickets = ticketService.getTickets(order.getId());
                boolean unroutable = order.getEmail() != null && order.getEmail().endsWith(StaffTestOrderService.TEST_EMAIL_DOMAIN);
                sb.append("<tr>");
                sb.append("<td>").append(order.getCreatedAt()).append("</td>");
                sb.append("<td><span class=\"test-badge\">ТЕСТ</span> ").append(order.getId()).append("</td>");
                sb.append("<td>").append(order.getOrderType() == OrderType.PRIVATE_BOAT ? "катер" : "билеты").append("</td>");
                sb.append("<td>").append(HtmlUtils.htmlEscape(StaffMailQueueController.maskEmail(order.getEmail()))).append("</td>");
                sb.append("<td>").append(describeTickets(tickets)).append("</td>");
                sb.append("<td>");
                if (tickets.isEmpty()) {
                    sb.append("—");
                } else {
                    sb.append("<a href=\"/staff/test-order/").append(order.getId()).append("/tickets.pdf\">скачать</a>");
                }
                sb.append("</td><td>");
                if (tickets.isEmpty() || unroutable) {
                    sb.append("<span class=\"muted\">").append(unroutable ? "служебный адрес" : "нет билетов").append("</span>");
                } else {
                    sb.append("<form method=\"post\" action=\"/staff/test-order/").append(order.getId())
                            .append("/send\" onsubmit=\"return confirm('Отправить тестовое письмо на указанный адрес?');\">");
                    sb.append(StaffPages.csrfField(StaffPages.csrfToken(request)));
                    sb.append("<button type=\"submit\">Отправить письмо</button></form>");
                    if (order.getTicketsEmailedAt() != null) {
                        sb.append("<span class=\"muted\">отправлено ").append(order.getTicketsEmailedAt()).append("</span>");
                    }
                }
                sb.append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append(StaffPages.close());
        return sb.toString();
    }

    @PostMapping
    public String create(@RequestParam(required = false) String flow,
                         @RequestParam(required = false) String email,
                         Authentication authentication) {
        try {
            boolean privateBoat = "boat".equals(flow);
            Order order = testOrders.createIssuedTestOrder(authentication.getName(), privateBoat, email);
            return redirect("msg", "Тестовый заказ создан, билет выпущен: " + order.getId());
        } catch (Exception e) {
            log.warn("Staff test order failed, errorType={}", e.getClass().getSimpleName());
            return redirect("error", "Не удалось создать тестовый заказ: " + e.getMessage());
        }
    }

    @PostMapping("/{orderId}/send")
    public String sendEmail(@PathVariable UUID orderId, Authentication authentication) {
        try {
            testOrders.sendTestEmail(orderId, authentication.getName());
            return redirect("msg", "Тестовое письмо отправлено по заказу " + orderId);
        } catch (Exception e) {
            log.warn("Staff test email failed for orderId={}, errorType={}", orderId, e.getClass().getSimpleName());
            return redirect("error", "Не удалось отправить письмо: " + e.getMessage());
        }
    }

    @GetMapping("/{orderId}/tickets.pdf")
    public ResponseEntity<byte[]> ticketsPdf(@PathVariable UUID orderId) {
        testOrders.requireTestOrder(orderId);
        byte[] pdf = pdfTicketService.buildTicketsPdfByOrderId(orderId, baseUrl);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"test-tickets-" + orderId + ".pdf\"")
                .body(pdf);
    }

    private String describeTickets(List<TicketResponse> tickets) {
        if (tickets.isEmpty()) {
            return "<span class=\"muted\">не выпущены</span>";
        }
        StringBuilder sb = new StringBuilder();
        for (TicketResponse ticket : tickets) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(HtmlUtils.htmlEscape(ticket.ticketType().name())).append(": ").append(statusText(ticket.ticketStatus()));
        }
        return sb.toString();
    }

    private String statusText(TicketStatus status) {
        return switch (status) {
            case ISSUED -> "действителен";
            case USED -> "использован";
            case EXPIRED -> "истёк";
            case REVOKED -> "аннулирован";
        };
    }

    private String redirect(String param, String message) {
        return "redirect:/staff/test-order?" + param + "=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }
}
