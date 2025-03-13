package com.watertours.project.service;

import com.watertours.project.enums.OrderStatus;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.model.entity.ticket.QuickTicket;
import com.watertours.project.repository.OrderRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class OrderService {
    private TicketOrder order;
    private final OrderRepository orderRepository;
    private  RedisTemplate<String, TicketOrder> redisTemplate;

    @Autowired
    public OrderService(OrderRepository orderRepository, RedisTemplate<String, TicketOrder> redisTemplate) {
        this.orderRepository = orderRepository;
        this.redisTemplate = redisTemplate;
    }

    public boolean simulatePayment(TicketOrder order){
        return false;

    }
    public TicketOrder getOrder(String cartId) {
        {
            TicketOrder order =   redisTemplate.opsForValue().get("cartId" + cartId);
            if (order == null) {
                order = new TicketOrder();
                redisTemplate.opsForValue().set("cartId" + cartId, order);
            }
            return order;
        }
    }
        public void saveOrderToRedis (String cartId, TicketOrder order){
            redisTemplate.opsForValue().set("cartId" + cartId, order);
        }

        public void clearOrderFromRedis (String cartId){
            redisTemplate.delete("cartId" + cartId);
        }

        public TicketOrder saveOrderToDB (TicketOrder order){
            TicketOrder resultOrder = null;
            try {
                if (isValidOrder(order.getTicketList(), order.getBuyerName(), order.getEmail(), order.getPhone())) {
                    resultOrder = orderRepository.save(order);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Не выбраны поля или не правильно заполнены данные");
            }
            orderRepository.save(order);
            return resultOrder;
        }

        //region PRIVATE METHODS


        private TicketOrder createOrder (HttpSession httpSession){

            this.order = (TicketOrder) httpSession.getAttribute("order");
            if (order == null) {
                order = new TicketOrder();
            }
            return order;
        }


        private boolean isValidOrder (List < QuickTicket > listTicket, String name, String email, String phone){
            return !listTicket.isEmpty() && name != null && email != null && phone != null;
        }

        public void changeStatusToPaid (TicketOrder order){
            Optional<TicketOrder> optionalOrder = orderRepository.findById(order.getId());
            if (optionalOrder.isPresent()) {
                TicketOrder orderFromDB = optionalOrder.get();
                orderFromDB.setStatus(OrderStatus.PAID);
                orderRepository.save(orderFromDB);
            }
        }
        //endregion
    }


