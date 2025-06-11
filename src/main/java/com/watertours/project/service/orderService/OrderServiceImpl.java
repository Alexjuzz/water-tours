package com.watertours.project.service.orderService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
public class OrderServiceImpl implements OrderService {
    private static final String REDIS_KEY_PREFIX = "order:";
    private final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final OrderRepository orderRepository;
    private final ObjectMapper mapper;

    public OrderServiceImpl(StringRedisTemplate redisTemplate, OrderRepository orderRepository, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.orderRepository = orderRepository;
        this.mapper = mapper;
    }

    @Override
    public TicketOrder getOrderById(String cartId) throws JsonProcessingException {
        String redisKey = redisKey(cartId);
        String jsonOrder = null;

        try {
            jsonOrder = redisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            logger.error("Redis is unavailable: {}", e.getMessage());
            throw new RuntimeException("Временные проблемы с сервисом. Попробуйте позже.");
        }
        if (jsonOrder != null) {
            try {
                return mapper.readValue(jsonOrder, TicketOrder.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            logger.error("Order with cartId {} not found in Redis", cartId);
            throw new RuntimeException();
        }
        return null;
    }


    @Override
    public void saveOrderToRedis(String cartId, TicketOrder order) throws JsonProcessingException {
        String redisKey = redisKey(cartId);
        String jsonOrder = null;
        try {
            jsonOrder = mapper.writeValueAsString(order);
        } catch (Exception e) {
            logger.error("Error serializing order to JSON: {}", e.getMessage());
            throw new JsonProcessingException("Ошибка сериализации заказа") {
            };
        }

        redisTemplate.opsForValue().set(redisKey, jsonOrder);
    }


    @Override
    public void changeStatus(String cartId, OrderStatus status) throws JsonProcessingException {
        TicketOrder order = null;
        try {
            order = getOrderById(cartId);

        } catch (Exception e) {
            logger.error("Error changing  order status with cartId {}: {}", cartId, e.getMessage());
            throw new RuntimeException("Ошибка изменения статуса заказа. Попробуйте позже.");
        }
        order.setStatus(status);
        saveOrderToRedis(cartId, order);
    }

    @Override
    public boolean isOrderPaid(String cartId) {
        TicketOrder order = null;
        try {
            order = getOrderById(cartId);
        } catch (JsonProcessingException e) {
            logger.error("Error retrieving order with cartId {}: {}", cartId, e.getMessage());
            throw new RuntimeException("Ошибка получения заказа. Попробуйте позже.");
        } catch (RuntimeException e) {
            logger.error("Order with cartId {} not found: {}", cartId, e.getMessage());
            return false;
        }
        return order.getStatus() == OrderStatus.PAID;
    }

    @Override
    public TicketOrder saveOrderToDatabase(TicketOrder order) {

        return orderRepository.save(order);
    }

    @Override
    public void clearOrderFromRedis(String cartId) {
        redisTemplate.delete(redisKey(cartId));
    }

    @Override
    public TicketOrder getOrderByIdFromDatabase(String cartId) throws JsonProcessingException {
        Optional<TicketOrder> order = orderRepository.findByCartId(cartId);
        return order.orElse(null);
    }


    private String redisKey(String cartId) {
        return REDIS_KEY_PREFIX + cartId;
    }

}
