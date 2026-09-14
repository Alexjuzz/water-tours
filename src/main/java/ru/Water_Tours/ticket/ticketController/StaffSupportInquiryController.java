package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.enums.SupportStatus;
import ru.Water_Tours.support.SupportInquiry;
import ru.Water_Tours.support.SupportInquiryRepository;
import ru.Water_Tours.support.SupportProperties;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Read-only recovery view for support inquiries.
 *
 * This exists for one failure mode: Telegram refused the owner notification and the retries ran
 * out, so a customer's question is stored but nobody has seen it. Without this page that question
 * is invisible until someone reads the database.
 *
 * It is deliberately not an inbox - no reply box, no search, no status editing, no pagination,
 * just the most recent inquiries. Answering happens in Telegram (/reply) or by writing to the
 * contact the customer left.
 */
@Controller
@RequestMapping("/staff/support-inquiries")
public class StaffSupportInquiryController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    private final SupportInquiryRepository repository;
    private final SupportProperties properties;

    public StaffSupportInquiryController(SupportInquiryRepository repository, SupportProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @GetMapping
    @ResponseBody
    public String page(HttpServletRequest request) {
        CsrfToken csrfToken = StaffPages.csrfToken(request);
        StringBuilder sb = new StringBuilder();
        sb.append(StaffPages.open("Вопросы клиентов", csrfToken));
        sb.append("<h1>Вопросы клиентов</h1>");

        if (!properties.isEnabled()) {
            sb.append("<p style=\"color:#8a4b00\">Приём вопросов выключен: не задан личный чат владельца "
                    + "(<code>SUPPORT_OWNER_TELEGRAM_CHAT_ID</code>). Форма на сайте и команда /question "
                    + "не принимают обращения.</p>");
        }

        sb.append("<p class=\"muted\">Только просмотр. Страница нужна, чтобы не потерять обращение, "
                + "уведомление о котором Telegram не принял (статус «не доставлено»). Отвечает владелец "
                + "командой <code>/reply &lt;номер&gt; &lt;текст&gt;</code> в Telegram; на обращения с сайта "
                + "нужно ответить по указанному контакту вручную — бот не пишет на почту и телефон.</p>");

        List<SupportInquiry> inquiries = repository.findTop50ByOrderByCreatedAtDesc();
        if (inquiries.isEmpty()) {
            sb.append("<p>Обращений пока нет.</p>");
        } else {
            sb.append("<table><tr><th>Номер</th><th>Когда</th><th>Источник</th><th>Статус</th>"
                    + "<th>Контакт для ответа</th><th>Вопрос</th></tr>");
            for (SupportInquiry inquiry : inquiries) {
                sb.append("<tr>");
                sb.append("<td><code>").append(HtmlUtils.htmlEscape(inquiry.getReference())).append("</code></td>");
                sb.append("<td>").append(STAMP.format(inquiry.getCreatedAt())).append("</td>");
                sb.append("<td>").append(inquiry.getSource() == SupportSource.WEBSITE ? "сайт" : "Telegram").append("</td>");
                sb.append("<td>").append(statusCell(inquiry)).append("</td>");
                sb.append("<td>").append(HtmlUtils.htmlEscape(contactOf(inquiry))).append("</td>");
                sb.append("<td>").append(HtmlUtils.htmlEscape(inquiry.getMessage())).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append(StaffPages.close());
        return sb.toString();
    }

    private String contactOf(SupportInquiry inquiry) {
        if (inquiry.getSource() == SupportSource.TELEGRAM) {
            // The chat id is the channel and is not useful to a human; showing it would only add
            // one more identifier to a page that does not need it.
            return "ответ в Telegram";
        }
        String contact = inquiry.getContact() == null ? "—" : inquiry.getContact();
        return inquiry.getOrderReference() == null
                ? contact
                : contact + " (заказ со слов клиента: " + inquiry.getOrderReference() + ")";
    }

    private String statusCell(SupportInquiry inquiry) {
        String label = switch (inquiry.getStatus()) {
            case NEW -> "ожидает отправки владельцу";
            case NOTIFIED -> "передано владельцу";
            case UNDELIVERED -> "НЕ ДОСТАВЛЕНО";
            case ANSWERED -> "отвечено";
        };
        String escaped = HtmlUtils.htmlEscape(label);
        if (inquiry.getStatus() == SupportStatus.UNDELIVERED) {
            return "<strong style=\"color:#b3261e\">" + escaped + "</strong> ("
                    + inquiry.getNotifyAttempts() + " попыт., "
                    + HtmlUtils.htmlEscape(String.valueOf(inquiry.getLastNotifyError())) + ")";
        }
        return escaped;
    }
}
