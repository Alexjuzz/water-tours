package ru.Water_Tours.ticket.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.component.TicketProperties;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.order.BoatRentalRequestDTO;
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
        Order newOrder = new Order();
        if (order.boatRental() != null) {
            if (order.tickets() != null && order.tickets().values().stream().anyMatch(qty -> qty != null && qty > 0)) {
                throw new IllegalArgumentException("Passenger tickets and a private boat cannot be combined in one order");
            }
            configurePrivateBoat(newOrder, order.boatRental());
        } else {
            configurePassengerOrder(newOrder, order.tickets());
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

    private void configurePassengerOrder(Order order, Map<TicketType, Integer> tickets) {
        if (tickets == null || tickets.isEmpty() || tickets.containsKey(TicketType.PRIVATE_BOAT)) {
            throw new IllegalArgumentException("Order must contain at least one passenger ticket");
        }
        order.setOrderType(OrderType.PASSENGER);
        order.setOrderItems(getOrderItems(order, tickets));
        if (order.getOrderItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one ticket with positive quantity");
        }
    }

    private void configurePrivateBoat(Order order, BoatRentalRequestDTO rental) {
        if (rental.durationMinutes() == null || rental.guestCount() == null || rental.routeType() == null) {
            throw new IllegalArgumentException("Boat rental duration, guest count and route type are required");
        }
        if (rental.guestCount() < 1 || rental.guestCount() > 6) {
            throw new IllegalArgumentException("Boat rental guest count must be between 1 and 6");
        }
        String routeNote = rental.routeNote() == null ? null : rental.routeNote().trim();
        if (routeNote != null && routeNote.length() > 300) {
            throw new IllegalArgumentException("Boat route note must not exceed 300 characters");
        }
        BigDecimal price = BoatRentalPricing.priceFor(rental.durationMinutes());
        order.setOrderType(OrderType.PRIVATE_BOAT);
        order.setBoatDurationMinutes(rental.durationMinutes());
        order.setBoatGuestCount(rental.guestCount());
        order.setBoatRouteType(rental.routeType());
        order.setBoatRouteNote(routeNote == null || routeNote.isEmpty() ? null : routeNote);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setType(TicketType.PRIVATE_BOAT);
        item.setQuantity(1);
        item.setPrice(price);
        item.setAmountPrice(price);
        order.setOrderItems(new ArrayList<>(List.of(item)));
    }

    public boolean expireIfPending(UUID id, java.time.Instant cutoff) {
        Order order = orderRepository.findByIdForUpdate(id).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.getCreatedAt().isBefore(cutoff)) return false;
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
        return true;
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
                order.getAccessToken(),
                null
        );
    }

    private List<OrderItem> getOrderItems(Order order, Map<TicketType, Integer> tickets) {
        if (tickets.isEmpty()) {
            return new ArrayList<>();
        }
        List<OrderItem> result = new ArrayList<>();
        for (Map.Entry<TicketType, Integer> entry : tickets.entrySet()) {
            if (entry.getKey() == null || entry.getKey() == TicketType.PRIVATE_BOAT) continue;
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
