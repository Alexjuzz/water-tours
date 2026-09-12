package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.service.PriceValues;
import ru.Water_Tours.ticket.service.PricingService;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Staff-only price editor (task 7.4): current values, publish (validated, versioned) and
 * rollback to any prior version. Spring stays the sole authoritative store - the storefront
 * (WordPress) only ever reads the published result via GET /api/v1/prices.
 */
@Controller
@RequestMapping("/staff/prices")
public class StaffPricingController {

    private static final Logger log = LoggerFactory.getLogger(StaffPricingController.class);

    private final PricingService pricingService;

    public StaffPricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping
    @ResponseBody
    public String page(@RequestParam(required = false) String msg,
                        @RequestParam(required = false) String error,
                        HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String csrfName = csrfToken != null ? csrfToken.getParameterName() : null;
        String csrfValue = csrfToken != null ? csrfToken.getToken() : null;

        PriceVersion current = pricingService.getCurrent();
        List<PriceVersion> history = pricingService.getHistory();

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\"><title>Цены</title></head><body>");
        sb.append("<h1>Цены и тарифы</h1>");
        if (msg != null) sb.append("<p style=\"color:green\">").append(HtmlUtils.htmlEscape(msg)).append("</p>");
        if (error != null) sb.append("<p style=\"color:red\">").append(HtmlUtils.htmlEscape(error)).append("</p>");

        sb.append("<h2>Текущая версия #").append(current.getVersionNumber()).append("</h2>");
        sb.append("<form method=\"post\" action=\"/staff/prices\">");
        csrfField(sb, csrfName, csrfValue);
        priceField(sb, "adultPrice", "Взрослый билет, ₽", current.getAdultPrice());
        priceField(sb, "childPrice", "Детский билет, ₽", current.getChildPrice());
        priceField(sb, "benefitPrice", "Льготный билет, ₽", current.getBenefitPrice());
        priceField(sb, "boatPrice30", "Аренда катера, 30 мин, ₽", current.getBoatPrice30());
        priceField(sb, "boatPrice60", "Аренда катера, 60 мин, ₽", current.getBoatPrice60());
        priceField(sb, "boatPrice90", "Аренда катера, 90 мин, ₽", current.getBoatPrice90());
        priceField(sb, "boatPrice120", "Аренда катера, 120 мин, ₽", current.getBoatPrice120());
        sb.append("<button type=\"submit\" onsubmit=\"return confirm('Опубликовать новые цены?');\">Опубликовать</button>");
        sb.append("</form>");

        sb.append("<h2>История</h2>");
        sb.append("<table border=\"1\" cellpadding=\"6\"><tr><th>Версия</th><th>Опубликовано</th><th>Кем</th><th>Взрослый</th><th>Детский</th><th>Льготный</th><th>Катер 30/60/90/120</th><th></th></tr>");
        for (PriceVersion version : history) {
            sb.append("<tr>");
            sb.append("<td>").append(version.getVersionNumber()).append("</td>");
            sb.append("<td>").append(version.getPublishedAt()).append("</td>");
            sb.append("<td>").append(HtmlUtils.htmlEscape(version.getPublishedBy())).append("</td>");
            sb.append("<td>").append(version.getAdultPrice()).append("</td>");
            sb.append("<td>").append(version.getChildPrice()).append("</td>");
            sb.append("<td>").append(version.getBenefitPrice()).append("</td>");
            sb.append("<td>").append(version.getBoatPrice30()).append("/").append(version.getBoatPrice60())
                    .append("/").append(version.getBoatPrice90()).append("/").append(version.getBoatPrice120()).append("</td>");
            sb.append("<td>");
            if (version.getVersionNumber() != current.getVersionNumber()) {
                sb.append("<form method=\"post\" action=\"/staff/prices/rollback/").append(version.getVersionNumber())
                        .append("\" onsubmit=\"return confirm('Вернуть цены версии #").append(version.getVersionNumber()).append("?');\">");
                csrfField(sb, csrfName, csrfValue);
                sb.append("<button type=\"submit\">Откатить</button></form>");
            } else {
                sb.append("текущая");
            }
            sb.append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    @PostMapping
    public String publish(@RequestParam BigDecimal adultPrice, @RequestParam BigDecimal childPrice,
                           @RequestParam BigDecimal benefitPrice, @RequestParam BigDecimal boatPrice30,
                           @RequestParam BigDecimal boatPrice60, @RequestParam BigDecimal boatPrice90,
                           @RequestParam BigDecimal boatPrice120, Authentication authentication) {
        try {
            PriceValues values = new PriceValues(adultPrice, childPrice, benefitPrice,
                    boatPrice30, boatPrice60, boatPrice90, boatPrice120);
            PriceVersion published = pricingService.publish(values, authentication.getName());
            return redirect("msg", "Опубликована версия #" + published.getVersionNumber());
        } catch (IllegalArgumentException e) {
            return redirect("error", e.getMessage());
        } catch (Exception e) {
            log.warn("Price publish failed, errorType={}", e.getClass().getSimpleName());
            return redirect("error", "Не удалось опубликовать цены: внутренняя ошибка.");
        }
    }

    @PostMapping("/rollback/{versionNumber}")
    public String rollback(@PathVariable int versionNumber, Authentication authentication) {
        try {
            PriceVersion published = pricingService.rollbackTo(versionNumber, authentication.getName());
            return redirect("msg", "Возвращены цены версии #" + versionNumber + " (новая версия #" + published.getVersionNumber() + ")");
        } catch (Exception e) {
            log.warn("Price rollback failed for versionNumber={}, errorType={}", versionNumber, e.getClass().getSimpleName());
            return redirect("error", "Не удалось откатить цены: " + e.getMessage());
        }
    }

    private String redirect(String paramName, String message) {
        return "redirect:/staff/prices?" + paramName + "=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private void csrfField(StringBuilder sb, String csrfName, String csrfValue) {
        if (csrfName != null && csrfValue != null) {
            sb.append("<input type=\"hidden\" name=\"").append(HtmlUtils.htmlEscape(csrfName))
                    .append("\" value=\"").append(HtmlUtils.htmlEscape(csrfValue)).append("\">");
        }
    }

    private void priceField(StringBuilder sb, String name, String label, BigDecimal value) {
        sb.append("<div><label>").append(HtmlUtils.htmlEscape(label)).append("</label> ");
        sb.append("<input type=\"number\" step=\"0.01\" min=\"0\" name=\"").append(name)
                .append("\" value=\"").append(value).append("\" required></div>");
    }
}
