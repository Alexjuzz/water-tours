package com.watertours.project.model.entity.order;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.ticket.QuickTicket;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@Data
@Entity
@Table(name = "ticker_order")
@ToString(exclude = "ticketList")
public class TicketOrder implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Serial
    private static final long serialVersionUID = 1;

    @JoinColumn(name = "ticket_list")
    @JsonManagedReference
    @OneToMany(cascade = CascadeType.ALL)
    private List<QuickTicket> ticketList = new ArrayList<>();

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "total_amount")
    private int totalAmount;
    @Column(name = "create_date")
    private Date createDate;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private OrderStatus status;


    @PrePersist
    private void prePersist() {
        this.status = OrderStatus.PENDING;
        this.createDate = new Date(System.currentTimeMillis());
    }


    public TicketOrder() {
    }



    public boolean addToTicketList(QuickTicket quickTicket) {
        if (quickTicket != null) {
            ticketList.add(quickTicket);
            return true;
        }
        return false;
    }

    public int getTicketPriceByType(TicketType type) {
        return ticketList.stream().
                filter(t -> t.getType().equals(type)).
                findFirst().
                map(QuickTicket::getPrice).
                orElse(new QuickTicket(type).getPrice());
    }

    public List<QuickTicket> deleteTicketByType(TicketType type) {
        Optional<QuickTicket> ticketToDelete = ticketList.stream()
                .filter(t -> t.getType().equals(type))
                .findFirst();
        ticketToDelete.ifPresent(ticketList::remove);
        return ticketList;
    }

    public QuickTicket getTicketByType(TicketType type) {
        Optional<QuickTicket> ticket = ticketList.stream()
                .filter(t -> t.getType().equals(type))
                .findFirst();
        return ticket.orElse(null);

    }


}
