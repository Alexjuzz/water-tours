package ru.Water_Tours.component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.OrderService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OrderExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationJob.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Duration expirationTimeout;

    public OrderExpirationJob(OrderRepository orderRepository,
                              OrderService orderService,
                              @Value("${order.expiration-timeout:30m}") Duration expirationTimeout) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.expirationTimeout = expirationTimeout;
    }

    @Scheduled(fixedDelayString = "${order.expiration-check-interval:300000}") // по умолчанию каждые 5 минут
    @Transactional
    public void expirePendingOrders() {
        Instant cutoff = Instant.now().minus(expirationTimeout);

        List<Order> expiredCandidates = orderRepository
                .findAllByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, cutoff);

        if (expiredCandidates.isEmpty()) {
            return;
        }

        log.info("Found {} orders to expire (older than {})", expiredCandidates.size(), expirationTimeout);

        for (Order order : expiredCandidates) {
            try {
                orderService.changeOrderStatus(order, OrderStatus.EXPIRED);
                log.info("Order {} marked as EXPIRED", order.getId());
            } catch (Exception e) {
                log.error("Failed to expire order {}", order.getId(), e);
            }
        }
    }
}