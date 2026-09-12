package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.util.HtmlUtils;

/** Shared chrome for the staff-only pages so every page carries the same navigation. */
final class StaffPages {

    private StaffPages() {
    }

    static CsrfToken csrfToken(HttpServletRequest request) {
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }

    static String csrfField(CsrfToken token) {
        if (token == null) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(token.getParameterName())
                + "\" value=\"" + HtmlUtils.htmlEscape(token.getToken()) + "\">";
    }

    static String open(String title, CsrfToken csrfToken) {
        return "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + HtmlUtils.htmlEscape(title) + "</title>"
                + "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0 auto;padding:0 16px 48px;"
                + "max-width:900px;color:#12242f}nav{display:flex;flex-wrap:wrap;align-items:center;gap:14px;padding:16px 0;"
                + "border-bottom:1px solid #d8e2e8;margin-bottom:24px}nav a{color:#0b6ea8;text-decoration:none;font-weight:600}"
                + "nav a:hover{text-decoration:underline}nav form{margin:0 0 0 auto}"
                + "table{border-collapse:collapse;width:100%}th,td{border:1px solid #d8e2e8;padding:8px;text-align:left;font-size:14px}"
                + "button{padding:8px 14px;border:0;border-radius:6px;background:#0b6ea8;color:#fff;font-weight:600;cursor:pointer}"
                + "nav button{background:none;color:#5b7180;padding:0;font-weight:600}"
                + "input,select{padding:6px}.muted{color:#5b7180}"
                + ".test-badge{background:#8a4b00;color:#fff;border-radius:4px;padding:1px 6px;font-size:12px}"
                + "</style></head><body>"
                + "<nav><a href=\"/staff\">Панель</a><a href=\"/staff/prices\">Цены</a><a href=\"/staff/refund\">Возвраты</a>"
                + "<a href=\"/staff/test-order\">Тестовый билет</a><a href=\"/staff/mail-queue\">Очередь писем</a>"
                + "<form method=\"post\" action=\"/logout\">" + csrfField(csrfToken) + "<button type=\"submit\">Выйти</button></form>"
                + "</nav>";
    }

    static String close() {
        return "</body></html>";
    }
}
