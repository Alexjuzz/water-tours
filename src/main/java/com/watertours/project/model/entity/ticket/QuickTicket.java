package com.watertours.project.model.entity.ticket;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.order.TicketOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
@Entity
@Table(name = "quick_ticket")
public class QuickTicket implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Serial
    private static final long serialVersionUID = 1L;
    @Column(name = "unique_id", unique = true)
    private  UUID uuid;
    @Column(name = "ticket_type")
    private  TicketType type;
    @Column(name = "price")
    private int price;

    public QuickTicket() {
    }

    @ManyToOne
    @JoinColumn(name = "TicketOrder_id")
    @JsonBackReference
    private TicketOrder order;

    public QuickTicket(TicketType type) {
        this.type = type;


    }





}
