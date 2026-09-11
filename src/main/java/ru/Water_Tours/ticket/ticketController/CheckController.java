package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import ru.Water_Tours.ticket.service.TicketService;

import java.util.NoSuchElementException;
import java.util.UUID;

@Controller
public class CheckController {

    private final TicketService ticketService;

    public CheckController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/t/{code}")
    @ResponseBody
    public String check(@PathVariable String code,
                        @RequestParam(required = false) String error,
                        HttpServletRequest request) {

        // Валидация формата code (защита от XSS и мусора)
        if (!isValidTicketCode(code)) {
            return buildNotFoundPage(code);
        }

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String csrfParameterName = csrfToken != null ? csrfToken.getParameterName() : null;
        String csrfTokenValue = csrfToken != null ? csrfToken.getToken() : null;

        String checkPage = ticketService.renderCheckPage(code, csrfParameterName, csrfTokenValue);

        if (error != null) {
            switch (error) {
                case "USED" -> checkPage = checkPage.replace("{{errorMessage}}", "Этот билет уже был использован.");
                case "NOT_VALID" -> checkPage = checkPage.replace("{{errorMessage}}", "Этот билет недействителен.");
                case "REFUND_HOLD" -> checkPage = checkPage.replace("{{errorMessage}}", "По этому заказу выполняется возврат. Посадка по билету недоступна.");
                case "NOT_FOUND" -> checkPage = checkPage.replace("{{errorMessage}}", "Билет не найден.");
                default -> checkPage = checkPage.replace("{{errorMessage}}", "Произошла неизвестная ошибка.");
            }
        } else {
            checkPage = checkPage.replace("{{errorMessage}}", "");
        }

        return checkPage;
    }

    @PostMapping("/t/{code}/redeem")
    public String redeemForCheckPage(@PathVariable String code) {
        if (!isValidTicketCode(code)) {
            return "redirect:/t/" + code + "?error=NOT_FOUND";
        }

        try {
            ticketService.redeemByCode(code);
            return "redirect:/t/" + code;
        } catch (IllegalArgumentException e) {
            return "redirect:/t/" + code + "?error=USED";
        } catch (IllegalStateException e) {
            boolean refundHold = e.getMessage() != null && e.getMessage().contains("refund is being processed");
            return "redirect:/t/" + code + (refundHold ? "?error=REFUND_HOLD" : "?error=NOT_VALID");
        } catch (NoSuchElementException e) {
            return "redirect:/t/" + code + "?error=NOT_FOUND";
        } catch (Exception e) {
            return "redirect:/t/" + code + "?error=UNKNOWN";
        }
    }

    private boolean isValidTicketCode(String code) {
        if (code == null || code.isBlank() || code.length() > 64) {
            return false;
        }
        // UUID или безопасный набор символов
        try {
            UUID.fromString(code);
            return true;
        } catch (IllegalArgumentException e) {
            // запасной вариант — только безопасные символы
            return code.matches("^[A-Za-z0-9\\-]{1,64}$");
        }
    }

    private String buildNotFoundPage(String code) {
        String safeCode = org.springframework.web.util.HtmlUtils.htmlEscape(code != null ? code : "");
        return "<html><body>" +
                "<div style=\"color:red\"></div>" +
                "<h1>Ticket with code: " + safeCode + " not found</h1>" +
                "</body></html>";
    }
}