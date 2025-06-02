package com.watertours.project.service.orderService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.repository.OrderRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


@Service
public class OrderServiceImpl implements OrderService {
        private static final String REDIS_KEY_PREFIX = "order:";

        private final StringRedisTemplate redisTemplate;
        private final OrderRepository orderRepository;
        private final ObjectMapper mapper;

    public OrderServiceImpl(StringRedisTemplate redisTemplate, OrderRepository orderRepository, ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.orderRepository = orderRepository;
        this.mapper = mapper;
    }


    // Implement the methods from OrderService interface here
    @Override
    public TicketOrder getOrderById(String cartId) throws JsonProcessingException {
        String redisKey = redisKey(cartId);
        String jsonOrder = redisTemplate.opsForValue().get(redisKey);
        if(jsonOrder != null) {
            try {
                return mapper.readValue(jsonOrder, TicketOrder.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        TicketOrder order = new TicketOrder();
        order.setCartId(cartId);
        order.setStatus(OrderStatus.DRAFT);
        saveOrderToRedis(cartId, order);
        return order;
    }



    @Override
    public void saveOrderToRedis(String cartId, TicketOrder order) throws JsonProcessingException {
        String redisKey  = redisKey(cartId);
        String jsonOrder = mapper.writeValueAsString(order);
        redisTemplate.opsForValue().set(redisKey, jsonOrder);
    }


    @Override
    public void changeStatus(String cartId, OrderStatus status) throws JsonProcessingException {
        TicketOrder order = getOrderById(cartId);
        order.setStatus(status);
        saveOrderToRedis(cartId, order);
    }

    @Override
    public boolean isOrderPaid(String cartId) throws JsonProcessingException {
        TicketOrder order  = getOrderById(cartId);
        return  order.getStatus() == OrderStatus.PAID;
    }

    @Override
    public TicketOrder saveOrderToDatabase(TicketOrder order) {

        return orderRepository.save(order);
    }

    @Override
    public void clearOrderFromRedis(String cartId) {
       redisTemplate.delete(redisKey(cartId));
    }


    private String redisKey(String cartId) {
        return REDIS_KEY_PREFIX + cartId;
    }

}
