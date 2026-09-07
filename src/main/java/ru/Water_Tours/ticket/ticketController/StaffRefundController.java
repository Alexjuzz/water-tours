package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;
import ru.Water_Tours.ticket.service.RefundService;

import java.util.List;
import java.util.UUID;

// Staff-only refund console: search an order by email or order ID, refund it in one click.
// Not linked from anywhere public; reachable only by staff who know the URL and are logged in.
@Controller
@RequestMapping("/staff/refund")
public class StaffRefundController {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final RefundService refundService;

    public StaffRefundController(OrderRepository orderRepository, PaymentRepository paymentRepository,
                                 TicketRepository ticketRepository, RefundService refundService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.refundService = refundService;
    }

    @GetMapping
    @ResponseBody
    public String page(@RequestParam(required = false) String query,
                       @RequestParam(required = false) String msg,
                       @RequestParam(required = false) String error,
                       HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String csrfName = csrfToken != null ? csrfToken.getParameterName() : null;
        String csrfValue = csrfToken != null ? csrfToken.getToken() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\"><title>Возврат заказа</title></head><body>");
        sb.append("<h1>Возврат заказа</h1>");

        if (msg != null) sb.append("<p style=\"color:green\">").append(HtmlUtils.htmlEscape(msg)).append("</p>");
        if (error != null) sb.append("<p style=\"color:red\">").append(HtmlUtils.htmlEscape(error)).append("</p>");

        sb.append("<form method=\"get\" action=\"/staff/refund\">");
        sb.append("<input type=\"text\" name=\"query\" placeholder=\"email клиента или ID заказа\" value=\"")
                .append(HtmlUtils.htmlEscape(query != null ? query : "")).append("\">");
        sb.append("<button type=\"submit\">Найти</button>");
        sb.append("</form>");

        if (query != null && !query.isBlank()) {
            List<Order> found = findOrders(query.trim());
            if (found.isEmpty()) {
                sb.append("<p>Ничего не найдено по запросу: ").append(HtmlUtils.htmlEscape(query)).append("</p>");
            } else {
                sb.append("<table border=\"1\" cellpadding=\"6\"><tr>")
                        .append("<th>Заказ</th><th>Email</th><th>Сумма</th><th>Статус заказа</th><th>Создан</th><th>Действие</th></tr>");
                for (Order order : found) {
                    sb.append("<tr>");
                    sb.append("<td>").append(order.getId()).append("</td>");
                    sb.append("<td>").append(HtmlUtils.htmlEscape(order.getEmail() != null ? order.getEmail() : "")).append("</td>");
                    sb.append("<td>").append(order.getTotalAmount()).append(" ₽</td>");
                    sb.append("<td>").append(order.getStatus()).append("</td>");
                    sb.append("<td>").append(order.getCreatedAt()).append("</td>");
                    sb.append("<td>").append(actionCell(order, csrfName, csrfValue)).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    @PostMapping("/{orderId}")
    public String refund(@PathVariable UUID orderId, @RequestParam(required = false) String query) {
        String redirectQuery = query != null && !query.isBlank() ? "?query=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) : "";
        String sep = redirectQuery.isEmpty() ? "?" : "&";
        try {
            RefundService.RefundResult result = refundService.refund(orderId);
            return "redirect:/staff/refund" + redirectQuery + sep + "msg="
                    + java.net.URLEncoder.encode("Возврат выполнен: " + result.amount() + " ₽, refundId=" + result.providerRefundId(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalStateException | java.util.NoSuchElementException e) {
            return "redirect:/staff/refund" + redirectQuery + sep + "error="
                    + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "redirect:/staff/refund" + redirectQuery + sep + "error="
                    + java.net.URLEncoder.encode("Возврат не выполнен: провайдер платежей недоступен или отклонил запрос.", java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String actionCell(Order order, String csrfName, String csrfValue) {
        if (order.getStatus() != OrderStatus.PAID) return "—";
        boolean alreadyRefunded = paymentRepository.findAllByOrderId(order.getId()).stream()
                .noneMatch(p -> p.getStatus() == ru.Water_Tours.enums.PaymentStatus.SUCCEEDED);
        if (alreadyRefunded) return "—";
        boolean anyUsed = ticketRepository.findAllByOrderId(order.getId()).stream()
                .anyMatch(t -> t.getTicketStatus() == TicketStatus.USED);
        if (anyUsed) return "билет уже использован — возврат недоступен";

        StringBuilder form = new StringBuilder();
        form.append("<form method=\"post\" action=\"/staff/refund/").append(order.getId()).append("\" onsubmit=\"return confirm('Вернуть ")
                .append(order.getTotalAmount()).append(" ₽ по этому заказу?');\">");
        if (csrfName != null && csrfValue != null) {
            form.append("<input type=\"hidden\" name=\"").append(HtmlUtils.htmlEscape(csrfName))
                    .append("\" value=\"").append(HtmlUtils.htmlEscape(csrfValue)).append("\">");
        }
        form.append("<button type=\"submit\">Вернуть</button>");
        form.append("</form>");
        return form.toString();
    }

    private List<Order> findOrders(String query) {
        try {
            UUID id = UUID.fromString(query);
            return orderRepository.findById(id).map(List::of).orElse(List.of());
        } catch (IllegalArgumentException notAUuid) {
            return orderRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(query);
        }
    }
}
