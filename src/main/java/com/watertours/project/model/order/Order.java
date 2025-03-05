package com.watertours.project.model.order;

import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.ticket.QuickTicket;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@Data
public class Order implements Serializable {
    @Serial
    private  static final long serialVersionUID = 1;

    private List<QuickTicket> ticketList = new ArrayList<>();
    private String buyerName;
    private String email;
    private String telephone;
    private int totalAmount;



    public boolean addToTicketList(QuickTicket quickTicket){
        if(quickTicket != null){
            ticketList.add(quickTicket);
            return true;
        }
        return false;
    }
    public int getTicketPriceByType(TicketType type){
       return  ticketList.stream().
               filter(t -> t.getType().equals(type)).
               findFirst().map(QuickTicket::getPrice).
               orElse(new QuickTicket(type).getPrice());
    }
    public List<QuickTicket> deleteTicketByType(TicketType type){
       Optional<QuickTicket> ticketToDelete = ticketList.stream().filter(t -> t.getType().equals(type)).findFirst();
       ticketToDelete.ifPresent(ticketList::remove);
        return ticketList;
    }
    public QuickTicket getTicketByType(TicketType type){
            Optional<QuickTicket> ticket = ticketList.stream().filter(t -> t.getType().equals(type)).findFirst();
        return ticket.orElse(null);

    }
}
