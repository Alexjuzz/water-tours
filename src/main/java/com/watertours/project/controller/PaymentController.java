package com.watertours.project.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.service.emailService.EmailService;
import com.watertours.project.service.paymentService.YooKassaService;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.slf4j.Logger;

@Controller
public class PaymentController {
    private final Logger logger = org.slf4j.LoggerFactory.getLogger(PaymentController.class);
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
        try {
            TicketOrder order = orderService.getOrderById(cartId);
            if (order == null) {
                logger.error("Вызван метод /payment, не найден заказ с cartId: {}", cartId);
                return "fragments/paymentFragments/errorFragment :: error";

            }
            if (order.getStatus() == OrderStatus.PAID) {
                logger.info("Вызван метод /payment  заказ {} был оплачен", cartId);
                return "redirect:/order-success"; // TODO Доделать страницу успеха
            }
            if (order.getStatus() != OrderStatus.EMAIL_CONFIRM) {
                logger.warn("Вызван метод /payment, заказ {} ожидает подтверждения email,текущий статус задказа: {}", cartId, order.getStatus());
                return "fragments/paymentFragments/errorFragment :: error";
            }
            int totalAmount = order.getTotalAmount();
            order.setStatus(OrderStatus.PENDING);
            orderService.saveOrderToRedis(cartId, order);

            String returnUrl = "https://8fb6-89-110-78-103.ngrok-free.app/finalize-order?cartId=" + cartId;
            String paymentUrl = yooKassaService.createPayment(cartId, totalAmount, returnUrl);
            return "redirect:" + paymentUrl;
        } catch (JsonProcessingException e) {
            logger.error("Ошибка метода /payment, не получилось конвертировать обьект order {}", e.getMessage());
            throw new RuntimeException(e);
        }

    }

    @GetMapping("/finalize-order")
    public String finalizeOrd(@RequestParam String cartId, @RequestParam String paymentId, Model model) {


        try {
            String paymentStatus = yooKassaService.checkPaymentStatus(paymentId);
            if (!"success".equals(paymentStatus)) {
                model.addAttribute("message", "Ошибка оплаты, платеж не прошел Попробуйте ещё раз");
                return "fragments/paymentFragments/errorFragment :: error";
            }

            TicketOrder order = orderService.getOrderById(cartId);
            if (order == null) {
                logger.warn("Вызван метод /finalize-order, не найден заказ с cartId: {}", cartId);
                return "fragments/paymentFragments/errorFragment :: error";
            }
            if (order.getStatus() == OrderStatus.PAID) {
                logger.info("Вызван метод /finalize-order, заказ {} уже оплачен", cartId);
                return "redirect:/order-success"; // TODO Доделать страницу успеха
            }
            orderService.changeStatus(cartId, OrderStatus.PAID);
            orderService.saveOrderToDatabase(order);
            try {
                emailService.sendTicketsEmail(order);
            } catch (Exception e) {
                logger.error("Ошибка отправки письма с билетами для заказа {}: {}", cartId, e.getMessage());
            }
            orderService.clearOrderFromRedis(cartId);
            return "redirect:/order-success";
        } catch (JsonProcessingException e) {
            logger.warn("Ошибка метода /finalize-order, не получилось конвертировать обьект order {}", e.getMessage());
            model.addAttribute("message", "Техническая ошибка. Попробуйте позже.");
            return "fragments/paymentFragments/errorFragment :: error";
        }
    }
}
