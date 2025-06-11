package com.watertours.project.interfaces.OrderService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.model.entity.order.TicketOrder;

public interface OrderService {
    TicketOrder getOrderById(String cartId) throws JsonProcessingException;
    void saveOrderToRedis(String cartId, TicketOrder order) throws JsonProcessingException;
    void changeStatus(String cartId, OrderStatus status) throws JsonProcessingException;
    boolean isOrderPaid(String cartId) throws JsonProcessingException;
    TicketOrder saveOrderToDatabase(TicketOrder order);
    void clearOrderFromRedis(String cartId);
    TicketOrder getOrderByIdFromDatabase(String cartId) throws JsonProcessingException;

}
