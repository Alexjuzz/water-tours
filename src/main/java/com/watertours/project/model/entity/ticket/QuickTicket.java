package com.watertours.project.model.entity.ticket;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.watertours.project.enums.TicketStatus;
import com.watertours.project.enums.TicketType;
import com.watertours.project.model.entity.order.TicketOrder;
import jakarta.persistence.*;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.Date;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Data
@Entity
@Table(name = "quick_ticket")
public class QuickTicket implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(QuickTicket.class);
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "unique_id", unique = true)
    private UUID uuid;
    @Column(name = "ticket_type")
    private TicketType type;
    @Column(name = "price")
    private int price;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private TicketStatus ticketStatus;
    @Column(updatable = false)
    private LocalDateTime dateStamp;

    public QuickTicket(String unique1, TicketType discount, Date date, int i,UUID uuid) {
        this.uuid = uuid;
        this.type = discount;
        this.dateStamp = date.toLocalDate().atStartOfDay();
        this.ticketStatus = TicketStatus.ACTIVE;
        this.price = i;
    }

    @ManyToOne
    @JoinColumn(name = "TicketOrder_id")
    @JsonBackReference
    private TicketOrder order;

    public QuickTicket(TicketType type) {
        this.type = type;
        this.uuid = UUID.randomUUID();
        this.ticketStatus = TicketStatus.ACTIVE;
        this.dateStamp = LocalDateTime.now();
    }

    public boolean useTicket() {
        if (ticketStatus == TicketStatus.ACTIVE) {
            ticketStatus = TicketStatus.USED;
            return true;
        }
        return false;
    }

    @JsonIgnore
    public boolean isExpired() {
        long diffDays = ChronoUnit.DAYS.between(dateStamp, LocalDateTime.now());
        if (diffDays > 5) {
            ticketStatus = TicketStatus.EXPIRED;
            return true;
        }
        return false;
    }


}
