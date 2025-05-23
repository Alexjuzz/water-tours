package com.watertours.project.service;

import com.watertours.project.enums.OrderStatus;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orderRepository;
    private final RedisTemplate<String, TicketOrder> redisTemplate;

    @Autowired
    public OrderService(OrderRepository orderRepository, RedisTemplate<String, TicketOrder> redisTemplate) {
        this.orderRepository = orderRepository;
        this.redisTemplate = redisTemplate;
    }

    public boolean simulatePayment(TicketOrder order) {
        return true;

    }

    public TicketOrder getOrder(String cartId) {
        TicketOrder order = redisTemplate.opsForValue().get("cartId:" + cartId);
        if (order == null) {
            logger.info("No order found in Redis for cartId: {}, creating new one", cartId);
            order = new TicketOrder();
            order.setCartId(cartId);
            redisTemplate.opsForValue().set("cartId:" + cartId, order, 5, TimeUnit.MINUTES);
        } else {
            logger.debug("Order retrieved from Redis for cartId: {}", cartId);
            int totalamount = 0;
            for (QuickTicket ticket : order.getTicketList()) {
                ticket.setOrder(order);
                totalamount += ticket.getPrice();
            }
            order.setTotalAmount(totalamount);
        }
        return order;
    }

    public void saveOrderToRedis(String cartId, TicketOrder order) {
        try {
            redisTemplate.opsForValue().set("cartId:" + cartId, order, 5, TimeUnit.MINUTES);
            logger.debug("Order saved to Redis for cartId: {}", cartId);
        } catch (Exception e) {
            logger.error("Failed to save order to Redis for cartId: {}", cartId, e);
        }
    }

    public void clearOrderFromRedis(String cartId) {
        try {
            redisTemplate.delete("cartId:" + cartId);
            logger.debug("Order cleared from Redis for cartId: {}", cartId);
        } catch (Exception e) {
            logger.error("Failed to clear order from Redis for cartId: {}", cartId, e);
        }
    }

    public boolean isOrderAllReadyPaid(String cartId) {
        return orderRepository.findByCartId(cartId).isPresent();
    }

    @Transactional
    public TicketOrder saveOrderToDB(TicketOrder order) {
        if (!isValidOrder(order.getTicketList(), order.getBuyerName(), order.getEmail(), order.getPhone())) {
            throw new IllegalArgumentException("Invalid order");
        }

        if (orderRepository.findByCartId(order.getCartId()).isPresent()) {
            logger.warn("Order with cartId {} already exists", order.getCartId());
            throw new IllegalArgumentException("Order with cartId already exists");
        }
        order.setStatus(OrderStatus.PENDING);
        for (QuickTicket ticket : order.getTicketList()) {
            ticket.setOrder(order);
        }
        TicketOrder savedOrder = orderRepository.save(order);
        logger.info("Order saved to DB with id: {}", savedOrder.getId());
        return savedOrder;
    }

    public void changeStatusOrder(TicketOrder order, OrderStatus status) {
        Optional<TicketOrder> optionalOrder = orderRepository.findById(order.getId());
        if (optionalOrder.isPresent()) {
            TicketOrder orderFromDB = optionalOrder.get();
            orderFromDB.setStatus(status);
            orderRepository.save(orderFromDB);
        }
    }

    public boolean checkOrderExist(TicketOrder order) {
        Optional<TicketOrder> optionalOrder = orderRepository.findById(order.getId());
        return optionalOrder.isPresent();
    }
    //region PRIVATE METHODS


    private boolean isValidOrder(List<QuickTicket> listTicket, String name, String email, String phone) {
        return !listTicket.isEmpty() && name != null && !name.isEmpty() && email != null && !email.isEmpty() && phone != null && !phone.isEmpty();
    }




    //endregion
}


