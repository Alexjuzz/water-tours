package com.watertours.project.service;

import com.watertours.project.model.order.Order;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private Order order;
    public boolean simulatePayment(Order order){
        return true;

    }

    //region PRIVATE METHODS
    private Order createOrder(HttpSession httpSession){
        this.order = (Order) httpSession.getAttribute("order");
        if (order == null){
            order = new Order();
        }
        return order;
    }

    public Order getOrder(HttpSession httpSession) {
        return createOrder(httpSession);
    }
}
