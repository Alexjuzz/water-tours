package ru.Water_Tours.ticket.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.component.TicketProperties;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.model.order.OrderResponse;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order createOrder(OrderRequestDTO order, String idempotencyKey) {
        if (order.tickets() == null || order.tickets().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one ticket.");
        }

        Order newOrder = new Order();
        newOrder.setOrderItems(getOrderItems(newOrder, order.tickets()));

        // Задача 8: после фильтрации qty <= 0 список не должен быть пустым
        if (newOrder.getOrderItems() == null || newOrder.getOrderItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one ticket with positive quantity.");
        }

        newOrder.setEmail(order.email());
        newOrder.setPhone(order.phoneNumber());
        newOrder.setTotalAmount(calculateTotalAmount(newOrder.getOrderItems()));

        // Задача 7: сохраняем idempotencyCode
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            newOrder.setIdempotencyCode(idempotencyKey);
        }

        return orderRepository.save(newOrder);
    }

    public void changeOrderStatus(Order order, OrderStatus status) {
        order.setStatus(status);
        orderRepository.save(order);
    }

    public OrderResponse getOrderResponse(UUID orderId, String idempotencyKey) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found with id: " + orderId));

        return new OrderResponse(
                orderId,
                idempotencyKey,
                order.getEmail(),
                order.getCreatedAt(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaidAt(),
                order.getPhone(),
                order.getAccessToken()
        );
    }

    private List<OrderItem> getOrderItems(Order order, Map<TicketType, Integer> tickets) {
        if (tickets.isEmpty()) {
            return new ArrayList<>();
        }
        List<OrderItem> result = new ArrayList<>();
        for (Map.Entry<TicketType, Integer> entry : tickets.entrySet()) {
            Integer qty = entry.getValue();
            if (qty == null || qty <= 0) continue;
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setPrice(TicketProperties.getPriceByType(entry.getKey()));
            orderItem.setType(entry.getKey());
            orderItem.setQuantity(entry.getValue());
            orderItem.setAmountPrice(calculateTicketPrice(entry.getKey(), entry.getValue()));
            result.add(orderItem);
        }
        return result;
    }
    public void checkAccess(UUID orderId, UUID accessToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        if (order.getAccessToken() == null || !order.getAccessToken().equals(accessToken)) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid access token");
        }
    }

    //REGION PRIVATE METHODS

    private BigDecimal calculateTicketPrice(TicketType ticketType, Integer count) {
        return TicketProperties.getPriceByType(ticketType).multiply(BigDecimal.valueOf(count));

    }

    private BigDecimal calculateTotalAmount(List<OrderItem> orderItemList) {
        BigDecimal bigDecimal = BigDecimal.ZERO;
        for (OrderItem orderItem : orderItemList) {
            if (orderItem.getAmountPrice() != null)
                bigDecimal = bigDecimal.add(orderItem.getAmountPrice());
        }
        return bigDecimal;
    }

    //END REGION PRIVATE METHODS
}
