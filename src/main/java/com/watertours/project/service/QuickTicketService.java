package com.watertours.project.service;

import com.watertours.project.component.TicketProperties;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.dto.QuickTicketModalDto;
import com.watertours.project.model.dto.TicketUpdateDto;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.model.order.Order;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.List;


//TODO 1. Заняться рефакторингом кода в классе QuickTicketService
//TODO 2. Перенести логику в контроллер
//


@Data
@Service
public class QuickTicketService {
    //Константы шаблонов
    private static final String QUICK_BUY_TICKET_FRAGMENT = "fragments/QuickBuyTicket :: QuickBuyTicketFragment";
    private static final String PROCEED_TO_USER_DATA_FRAGMENT = "fragments/proceedToUserData :: proceedToUserDataFragment";
    private static final String BASKET_FRAGMENT = "fragments/basket :: basketFragment";
    private static final String TICKET_FRAGMENT = "fragments/QuickBuyTicket :: %sTicketFragment";

    //Константы атрибутов
    private static final String CHILD_COUNT = "CHILDCount";
    private static final String SENIOR_COUNT = "SENIORCount";
    private static final String DISCOUNT_COUNT = "DISCOUNTCount";
    private static final String CHILD_AMOUNT = "CHILDAmount";
    private static final String SENIOR_AMOUNT = "SENIORAmount";
    private static final String DISCOUNT_AMOUNT = "DISCOUNTAmount";
    private static final String TOTAL_AMOUNT = "totalAmount";
    private static final String CHILD_PRICE = "CHILDPrice";
    private static final String SENIOR_PRICE = "SENIORPrice";
    private static final String DISCOUNT_PRICE = "DISCOUNTPrice";


    //Сервисы
    private final TicketService ticketService;
    private final OrderService orderService;
    private final TicketProperties properties;

    //region public methods
    @Autowired
    public QuickTicketService(TicketService ticketService, OrderService orderService, TicketProperties properties) {
        this.ticketService = ticketService;
        this.orderService = orderService;
        this.properties = properties;
    }

    public String getModal(HttpSession httpSession, Model model) {
        Order order = orderService.getOrder(httpSession);
        httpSession.setAttribute("order", order);
        model = addAtributesToModel(model);
        model.addAttribute("CHILDCount", getTicketCount(order.getTicketList(), TicketType.CHILD));
        model.addAttribute("SENIORCount", getTicketCount(order.getTicketList(), TicketType.SENIOR));
        model.addAttribute("DISCOUNTCount", getTicketCount(order.getTicketList(), TicketType.DISCOUNT));
        model.addAttribute("CHILDAmount", getTicketsPrice(getTicketCount(order.getTicketList(), TicketType.CHILD), properties.getChild()));

        return "fragments/QuickBuyTicket :: QuickBuyTicketFragment";
    }

    public String postModal(Model model, HttpSession httpSession) {
        Order getOrder = (Order) httpSession.getAttribute("order");
        System.out.println(getOrder);
        return "fragments/QuickBuyTicket :: QuickBuyTicketFragment";
    }

    public String proceedToUserData() {
        return "fragments/proceedToUserData :: proceedToUserDataFragment";
    }

    public TicketUpdateDto incrementTicket(TicketType type, Order order) {
        QuickTicket ticket = ticketService.createQuickTicket(type);
        order.addToTicketList(ticket);
        return createTicketUpdateDto(type, order);
    }

    public TicketUpdateDto decrementTicket(TicketType type, Order order) {
        order.deleteTicketByType(type);
        return createTicketUpdateDto(type, order);
    }

    public String getBasket(Model model, HttpSession httpSession) {
        Order order = orderService.getOrder(httpSession);
        httpSession.setAttribute("order", order);
        int totalAmount = getTotalAmount(order.getTicketList());
        model.addAttribute("totalAmount", totalAmount);
        return "fragments/basket :: basketFragment";
    }

    public QuickTicketModalDto prepareModalDto(Order order) {
        QuickTicketModalDto dto = new QuickTicketModalDto();
        dto.setChildCount(getTicketCount(order.getTicketList(), TicketType.CHILD));
        dto.setSeniorCount(getTicketCount(order.getTicketList(), TicketType.SENIOR));
        dto.setDiscountCount(getTicketCount(order.getTicketList(), TicketType.DISCOUNT));
        dto.setChildAmount(getTicketsPrice(dto.getChildCount(), properties.getChild()));
        dto.setSeniorAmount(getTicketsPrice(dto.getSeniorCount(), properties.getSenior()));
        dto.setDiscountAmount(getTicketsPrice(dto.getDiscountCount(), properties.getDiscount()));
        dto.setTotalAmount(getTotalAmount(order.getTicketList()));
        dto.setChildPrice(properties.getChild());
        dto.setSeniorPrice(properties.getSenior());
        dto.setDiscountPrice(properties.getDiscount());
        return dto;
    }

//endregion

    private Long getTicketCount(List<QuickTicket> ticketList, TicketType type) {
        return ticketList.stream().filter(t -> t.getType() == type).count();
    }

    private int getTotalAmount(List<QuickTicket> ticketList) {
        int summary = 0;
        for (QuickTicket quickTicket : ticketList) {
            summary += quickTicket.getPrice();
        }
        return summary;
    }

    private int getTicketsPrice(Long count, int price) {
        int result = (int) (count * price);
        return result;
    }

    private Model addAtributesToModel(Model model) {
        model.addAttribute("CHILDPrice", properties.getChild());
        model.addAttribute("SENIORPrice", properties.getSenior());
        model.addAttribute("DISCOUNTPrice", properties.getDiscount());
        model.addAttribute("CHILDCount", 0L);
        model.addAttribute("SENIORCount", 0L);
        model.addAttribute("DISCOUNTCount", 0L);
        model.addAttribute("totalAmount", 0L);
        return model;
    }

    private TicketUpdateDto createTicketUpdateDto(TicketType type, Order order) {
        long count = getTicketCount(order.getTicketList(), type);
        int price = properties.getPriceByType(type);
        int totalAmountTicket = getTicketsPrice(count, price);
        int totalAmount = getTotalAmount(order.getTicketList());
        return new TicketUpdateDto(type, count, price, totalAmountTicket, totalAmount);
    }


    //endregion
}
