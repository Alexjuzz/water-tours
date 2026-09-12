package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * Landing page after a staff login. Before this existed, a successful login left the browser on
 * whatever page Spring Security defaulted to, which told the operator nothing about what they can
 * actually do.
 */
@Controller
@RequestMapping("/staff")
public class StaffHomeController {

    @GetMapping
    @ResponseBody
    public String page(Authentication authentication, HttpServletRequest request) {
        String user = authentication != null ? authentication.getName() : "";

        StringBuilder sb = new StringBuilder();
        sb.append(StaffPages.open("Панель персонала", StaffPages.csrfToken(request)));
        sb.append("<h1>Панель персонала</h1>");
        sb.append("<p class=\"muted\">Вы вошли как <strong>").append(HtmlUtils.htmlEscape(user)).append("</strong>.</p>");

        sb.append("<h2><a href=\"/staff/prices\">Цены и тарифы</a></h2>");
        sb.append("<p>Изменить цены билетов и аренды катера. Публикация создаёт новую версию, старую можно вернуть одной кнопкой. ")
                .append("Уже созданные заказы считаются по той версии, что действовала на момент заказа.</p>");

        sb.append("<h2><a href=\"/staff/refund\">Возвраты</a></h2>");
        sb.append("<p>Найти заказ по email или номеру и оформить возврат. Возврат проходит через платёжного провайдера — это реальные деньги.</p>");

        sb.append("<h2><a href=\"/staff/test-order\">Тестовый билет</a></h2>");
        sb.append("<p>Создать помеченный тестовый заказ и получить по нему PDF-билет: без оплаты, без письма покупателю и без возврата. ")
                .append("Нужно, чтобы проверить выдачу билета, не проводя настоящий платёж.</p>");

        sb.append("<h2>Проверка билета на входе</h2>");
        sb.append("<p>Откройте ссылку из QR-кода билета — она ведёт на страницу вида <code>/t/&lt;код&gt;</code>, ")
                .append("где виден статус и есть кнопка погашения. Тот же билет можно проверить и погасить в Telegram-боте, ")
                .append("если он подключён.</p>");

        sb.append(StaffPages.close());
        return sb.toString();
    }
}
