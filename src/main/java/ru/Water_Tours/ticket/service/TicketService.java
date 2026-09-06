package ru.Water_Tours.ticket.service;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;
import org.springframework.web.util.HtmlUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TicketService {
    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;

    public TicketService(TicketRepository ticketRepository, OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
    }


    public List<Ticket> createTicketsFromItem(Order order) {
        List<Ticket> resultList = new ArrayList<>();

        for (OrderItem orderItem : order.getOrderItems()) {

            for (int i = 0; i < orderItem.getQuantity(); i++) {
                Ticket resultTicket = createTicketByType(order, orderItem.getType());
                resultList.add(resultTicket);
            }
        }
        return resultList;
    }

    @Transactional
    public List<TicketResponse> issueTickets(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new NoSuchElementException("Order with id " + orderId + " not found"));

        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order with id:  " + order.getId() + " is not paid");
        }

        if (order.getTicketIssuedAt() != null) {
            return convertListTicketToTicketResponse(ticketRepository.findAllByOrderId(orderId));
        }
        List<Ticket> existing = ticketRepository.findAllByOrderId(orderId);
        if (!existing.isEmpty()) {
            order.setTicketIssuedAt(Instant.now());
            orderRepository.save(order);
            return convertListTicketToTicketResponse(existing);
        }
//        if (ticketRepository.existsByOrderId(order.getId())) return ticketRepository.findAllByOrderId(orderId);
        List<Ticket> ticketList = ticketRepository.saveAll(createTicketsFromItem(order));
        order.setTicketIssuedAt(Instant.now());
        orderRepository.save(order);

        return convertListTicketToTicketResponse(ticketList);

    }


    private Ticket createTicketByType(Order order, TicketType type) {
        Ticket ticket = new Ticket();
        ticket.setCode(UUID.randomUUID().toString());
        ticket.setPurchaseDate(Instant.now());
        ticket.setValidTo(Instant.now().plus(3, ChronoUnit.DAYS));
        ticket.setValidFrom(Instant.now());
        ticket.setOrder(order);
        ticket.setTicketStatus(TicketStatus.ISSUED);
        ticket.setPurchaseEmail(order.getEmail());
        ticket.setTicketType(type);
        return ticket;
    }

    public List<TicketResponse> getTickets(UUID orderId) {
        List<Ticket> order = ticketRepository.findAllByOrderId(orderId);

        return convertListTicketToTicketResponse(order);

    }

    @Transactional
    public TicketResponse redeemByCode(String code) {
        Ticket t = ticketRepository.findByCodeForUpdate(code).orElseThrow(() -> new NoSuchElementException("Ticket with id " + code + " not found"));

        if (t.getTicketStatus() == TicketStatus.USED) {
            throw new IllegalArgumentException("Ticket with id " + code + " is already used");
        }

        Instant now = Instant.now();
        if (t.getValidTo().isBefore(now) || t.getValidFrom().isAfter(now)) {
            throw new IllegalStateException("Ticket with id " + code + " is not valid at the moment");
        }
        t.setTicketStatus(TicketStatus.USED);
        t.setUsedAt(now);
        ticketRepository.save(t);
        return new TicketResponse(t.getId(),
                t.getCode(),
                t.getPurchaseEmail(),
                t.getValidFrom(),
                t.getValidTo(),
                t.getPurchaseDate(),
                t.getTicketType(),
                t.getTicketStatus());
    }


    private List<TicketResponse> convertListTicketToTicketResponse(List<Ticket> tickets) {
        List<TicketResponse> result = new ArrayList<>();

        for (Ticket t : tickets) {
            result.add(new TicketResponse(t.getId(),
                    t.getCode(),
                    t.getPurchaseEmail(),
                    t.getValidFrom(),
                    t.getValidTo(),
                    t.getPurchaseDate(),
                    t.getTicketType(),
                    t.getTicketStatus()));

        }
        return result;
    }


// ... остальные импорты

    public String renderCheckPage(String code, String csrfParameterName, String csrfToken) {
        // Защита от XSS: экранируем всё, что попадает в HTML
        String safeCode = HtmlUtils.htmlEscape(code != null ? code : "");

        Optional<Ticket> ticket = ticketRepository.findByCode(code);
        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now();

        if (ticket.isPresent()) {
            Ticket t = ticket.get();

            String safeEmail = HtmlUtils.htmlEscape(t.getPurchaseEmail() != null ? t.getPurchaseEmail() : "");
            String safeValidFrom = HtmlUtils.htmlEscape(String.valueOf(t.getValidFrom()));
            String safeValidTo = HtmlUtils.htmlEscape(String.valueOf(t.getValidTo()));
            String safePurchaseDate = HtmlUtils.htmlEscape(String.valueOf(t.getPurchaseDate()));
            String safeType = HtmlUtils.htmlEscape(String.valueOf(t.getTicketType()));
            String safeStatus = HtmlUtils.htmlEscape(String.valueOf(t.getTicketStatus()));

            if (t.getTicketStatus() == TicketStatus.USED) {
                sb.append("<html><body>");
                sb.append("<div style=\"color:red\">{{errorMessage}}</div>");
                sb.append("<h1>Ticket with code: ").append(safeCode).append(" is already used</h1>");
                sb.append("<p>Purchase email: ").append(safeEmail).append("</p>");
                sb.append("<p>Valid from: ").append(safeValidFrom).append("</p>");
                sb.append("<p>Valid to: ").append(safeValidTo).append("</p>");
                sb.append("<p>Purchase date: ").append(safePurchaseDate).append("</p>");
                sb.append("<p>Ticket type: ").append(safeType).append("</p>");
                sb.append("<p>Ticket status: ").append(safeStatus).append("</p>");
                sb.append("</body></html>");
                return sb.toString();
            }

            if (t.getValidTo().isBefore(now) || t.getValidFrom().isAfter(now)) {
                sb.append("<html><body>");
                sb.append("<div style=\"color:red\">{{errorMessage}}</div>");
                sb.append("<h1>Ticket with code: ").append(safeCode).append(" is not valid at the moment</h1>");
                sb.append("<p>Purchase email: ").append(safeEmail).append("</p>");
                sb.append("<p>Valid from: ").append(safeValidFrom).append("</p>");
                sb.append("<p>Valid to: ").append(safeValidTo).append("</p>");
                sb.append("<p>Purchase date: ").append(safePurchaseDate).append("</p>");
                sb.append("<p>Ticket type: ").append(safeType).append("</p>");
                sb.append("<p>Ticket status: ").append(safeStatus).append("</p>");
                sb.append("</body></html>");
                return sb.toString();
            }

            if (t.getTicketStatus() == TicketStatus.ISSUED) {
                sb.append("<html><body>");
                sb.append("<div style=\"color:red\">{{errorMessage}}</div>");
                sb.append("<h1>Ticket with code ").append(safeCode).append("</h1>");
                sb.append("<p>Purchase email: ").append(safeEmail).append("</p>");
                sb.append("<p>Valid from: ").append(safeValidFrom).append("</p>");
                sb.append("<p>Valid to: ").append(safeValidTo).append("</p>");
                sb.append("<p>Purchase date: ").append(safePurchaseDate).append("</p>");
                sb.append("<p>Ticket type: ").append(safeType).append("</p>");
                sb.append("<p>Ticket status: ").append(safeStatus).append("</p>");

                // Форма с CSRF-токеном (задача 4)
                sb.append("<form method=\"post\" action=\"/t/").append(safeCode).append("/redeem\">");
                if (csrfParameterName != null && csrfToken != null) {
                    sb.append("<input type=\"hidden\" name=\"")
                            .append(HtmlUtils.htmlEscape(csrfParameterName))
                            .append("\" value=\"")
                            .append(HtmlUtils.htmlEscape(csrfToken))
                            .append("\">");
                }
                sb.append("<button type=\"submit\">Redeem</button>");
                sb.append("</form>");
                sb.append("</body></html>");
                return sb.toString();
            }
        }

        // Билет не найден
        sb.append("<html><body>");
        sb.append("<div style=\"color:red\">{{errorMessage}}</div>");
        sb.append("<h1>Ticket with code: ").append(safeCode).append(" not found</h1>");
        sb.append("</body></html>");
        return sb.toString();
    }
}