package ru.Water_Tours.ticket.service;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Profile("local-checkout")
public class LocalCheckoutService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    public LocalCheckoutService(OrderRepository orderRepository, PaymentRepository paymentRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.clock = clock;
    }

    @Transactional
    public Order confirmTestPayment(UUID orderId, UUID accessToken) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        checkAccess(order, accessToken);

        if (order.getStatus() == OrderStatus.PAID) {
            if (!Boolean.TRUE.equals(order.getTestPaid())) {
                throw new IllegalStateException("Order " + orderId + " already has a real payment");
            }
            return order;
        }
        if (order.getStatus() != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be confirmed with a test payment");
        }
        if (!paymentRepository.findAllByOrderId(orderId).isEmpty()) {
            throw new IllegalStateException("Order " + orderId + " already has a real payment attempt");
        }

        order.setStatus(OrderStatus.PAID);
        order.setTestPaid(true);
        order.setPaidAt(Instant.now(clock));
        return orderRepository.save(order);
    }

    public Order getOwnedOrder(UUID orderId, UUID accessToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        checkAccess(order, accessToken);
        return order;
    }

    private void checkAccess(Order order, UUID accessToken) {
        if (order.getAccessToken() == null || !order.getAccessToken().equals(accessToken)) {
            throw new AccessDeniedException("Invalid access token");
        }
    }
}
