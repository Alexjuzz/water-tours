package com.watertours.project.service;

import com.watertours.project.component.TicketProperties;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.ticket.QuickTicket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;



@Service
public class TicketService {
    private List<QuickTicket> listTicket;
    private final TicketProperties ticketProperties;

    @Autowired
    public TicketService(TicketProperties ticketProperties) {
        this.ticketProperties = ticketProperties;
    }


    public QuickTicket createQuickTicket(TicketType type){
        QuickTicket quickTicket = new QuickTicket(type);

        switch (type) {
            case CHILD:
                quickTicket.setPrice(ticketProperties.getChild());
                break;
            case SENIOR:
                quickTicket.setPrice(ticketProperties.getSenior());
                break;
            case DISCOUNT:
                quickTicket.setPrice(ticketProperties.getDiscount());
                break;
            default:
                throw  new IllegalArgumentException("Не правильный тип билета");
        }
        return quickTicket;
    }

    public List<QuickTicket> getListTicket() {
        return listTicket;
    }
}
