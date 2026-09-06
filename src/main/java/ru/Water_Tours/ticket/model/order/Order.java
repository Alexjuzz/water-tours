package ru.Water_Tours.ticket.model.order;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.ticket.Ticket;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status")
    private OrderStatus status;
    @Column(name = "idempotency_code", unique = true)
    private String idempotencyCode;
    @Column(name = "tickets_emailed_at")
    private Instant ticketsEmailedAt;
    @Column(name = "access_token", unique = true, nullable = false)
    private UUID accessToken;
    @Column(name = "telegram_chat_id")
    private Long telegramChatId;


    @Column(name = "email")
    private String email;
    @Column(name = "phone")
    private String phone;
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "tickets_issued_at")
    private Instant ticketIssuedAt;

    @Column(name = "test_paid", nullable = false, columnDefinition = "boolean default false")
    private Boolean testPaid = false;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<Ticket> ticketList;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems;


    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = OrderStatus.DRAFT;
        if(accessToken  == null) accessToken = UUID.randomUUID();
        if(testPaid == null) testPaid = false;
    }

}
