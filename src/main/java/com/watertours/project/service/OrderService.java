package com.watertours.project.service;

import com.watertours.project.enums.OrderStatus;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.repository.OrderRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private TicketOrder order;
    private final OrderRepository orderRepository;
    private RedisTemplate<String, TicketOrder> redisTemplate;

    @Autowired
    public OrderService(OrderRepository orderRepository, RedisTemplate<String, TicketOrder> redisTemplate) {
        this.orderRepository = orderRepository;
        this.redisTemplate = redisTemplate;
    }

    public boolean simulatePayment(TicketOrder order) {
        return true;

    }

    public TicketOrder getOrder(String cartId) {
        TicketOrder order;
        try {
            order = redisTemplate.opsForValue().get("cartId" + cartId);
            if (order == null) {
                logger.info("No order found in Redis for cartId: {}, creating new one", cartId);
                order = new TicketOrder();
                redisTemplate.opsForValue().set("cartId" + cartId, order, 3, TimeUnit.MINUTES);
            } else {
                logger.debug("Order retrieved from Redis for cartId: {}", cartId);
                for (QuickTicket ticket : order.getTicketList()) {
                    ticket.setOrder(order);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for cartId: {}. Creating new order", cartId, e);
            order = new TicketOrder();
        }
        return order;
    }

    public void saveOrderToRedis(String cartId, TicketOrder order) {
        try {
            redisTemplate.opsForValue().set("cartId" + cartId, order, 3, TimeUnit.MINUTES);
            logger.debug("Order saved to Redis for cartId: {}", cartId);
        } catch (Exception e) {
            logger.error("Failed to save order to Redis for cartId: {}", cartId, e);
        }
    }

    public void clearOrderFromRedis(String cartId) {
        try {
            redisTemplate.delete("cart:" + cartId);
            logger.debug("Order cleared from Redis for cartId: {}", cartId);
        } catch (Exception e) {
            logger.error("Failed to clear order from Redis for cartId: {}", cartId, e);
        }
    }



    public TicketOrder saveOrderToDB(TicketOrder order) {
        if (!isValidOrder(order.getTicketList(), order.getBuyerName(), order.getEmail(), order.getPhone())) {
            throw new IllegalArgumentException("Invalid order");
        }
        for (QuickTicket ticket : order.getTicketList()) {
            ticket.setOrder(order);
        }
        return orderRepository.save(order);
    }

    public void changeStatusToPaid(TicketOrder order) {
        Optional<TicketOrder> optionalOrder = orderRepository.findById(order.getId());
        if (optionalOrder.isPresent()) {
            TicketOrder orderFromDB = optionalOrder.get();
            orderFromDB.setStatus(OrderStatus.PAID);
            orderRepository.save(orderFromDB);
        }
    }

    //region PRIVATE METHODS


    private TicketOrder createOrder(HttpSession httpSession) {

        this.order = (TicketOrder) httpSession.getAttribute("order");
        if (order == null) {
            order = new TicketOrder();
        }
        return order;
    }


    private boolean isValidOrder(List<QuickTicket> listTicket, String name, String email, String phone) {
        return !listTicket.isEmpty() && name != null && !name.isEmpty() && email != null && !email.isEmpty() && phone != null && !phone.isEmpty();
    }


    //endregion
}


