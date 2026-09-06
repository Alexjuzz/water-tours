package ru.Water_Tours.ticket.model.ticket;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Data;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "tickets")
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_status",nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketStatus ticketStatus;
    @Column(name = "ticket_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketType ticketType;

    @Column(name = "code",unique = true,nullable = false,length = 64)
    private String code;

    @Column(name = "purchase_email")
    private String purchaseEmail;

    @Column(name = "purchase_date")
    private Instant purchaseDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JsonBackReference
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

//    @Column(name = "valid_status")
//    private Boolean validStatus;

    @Column(name = "validFrom")
    private Instant validFrom;

    @Column(name = "validTo")
    private Instant validTo;

    @Column(name = "used_at")
    private  Instant usedAt;


}



