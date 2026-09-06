package ru.Water_Tours.ticket.model.OrderItem;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Getter
@Setter
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "ticket_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketType type;


    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "amount_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPrice;


}
