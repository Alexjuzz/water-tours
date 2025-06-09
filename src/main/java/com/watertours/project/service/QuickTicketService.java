package com.watertours.project.service;

import com.watertours.project.component.TicketProperties;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.dto.BasketUpdateDto;
import com.watertours.project.model.dto.QuickTicketModalDto;
import com.watertours.project.model.dto.TicketUpdateDto;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.interfaces.OrderService.OrderService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Data
@Service
public class QuickTicketService {
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

    public TicketUpdateDto incrementTicket(TicketType type, TicketOrder order) {
        QuickTicket ticket = ticketService.createQuickTicket(type);
        order.addToTicketList(ticket);
        ticket.setOrder(order);
        return createTicketUpdateDto(type, order);
    }

    public TicketUpdateDto decrementTicket(TicketType type, TicketOrder order) {
        order.deleteTicketByType(type);
        return createTicketUpdateDto(type, order);
    }

    public BasketUpdateDto getBasket(TicketOrder order) {
        BasketUpdateDto dto = new BasketUpdateDto();
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

    public QuickTicketModalDto prepareModalDto(TicketOrder order) {
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
    //region private methods

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
        return  (int) (count * price);
    }

    private TicketUpdateDto createTicketUpdateDto(TicketType type, TicketOrder order) {
        long count = getTicketCount(order.getTicketList(), type);
        int price = properties.getPriceByType(type);
        int totalAmountTicket = getTicketsPrice(count, price);
        int totalAmount = getTotalAmount(order.getTicketList());
        return new TicketUpdateDto(type, count, price, totalAmountTicket, totalAmount);
    }


    //endregion
}
