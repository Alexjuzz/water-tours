package com.watertours.project.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.service.emailService.EmailService;
import com.watertours.project.service.paymentService.YooKassaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PaymentController {
    private final OrderService orderService;
    private final YooKassaService yooKassaService;
    private final EmailService emailService;

    @Autowired
    public PaymentController(OrderService orderService, YooKassaService yooKassaService, EmailService emailService) {
        this.orderService = orderService;
        this.yooKassaService = yooKassaService;
        this.emailService = emailService;
    }

    @GetMapping("/payment")
    public String payment(@RequestParam String cartId) {
        int total = 0;
        try {
            total = orderService.getOrderById(cartId).getTotalAmount();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        String returnUrl = "https://8fb6-89-110-78-103.ngrok-free.app/finalize-order";
        String paymentUrl = yooKassaService.createPayment(cartId, total, returnUrl);
        return "redirect:" + paymentUrl;
    }

    @GetMapping("/finalize-order")
    public String finalizeOrd(@RequestParam String cartId, Model model) {
        try {
            orderService.changeStatus(cartId, OrderStatus.PAID);
            TicketOrder paidOrder = orderService.getOrderById(cartId);
            TicketOrder savedOrder = orderService.saveOrderToDatabase(paidOrder);
            emailService.sendTicketsEmail(savedOrder);
            orderService.clearOrderFromRedis(cartId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        model.addAttribute("message", "Оплата прошла успешно! Ваш заказ подтвержден.");
        return "redirect:/order-success"; // Перенаправление на страницу успеха
    }
}
