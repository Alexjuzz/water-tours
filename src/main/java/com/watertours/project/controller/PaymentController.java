package com.watertours.project.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.interfaces.email.TicketEmailService;
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
    private final TicketEmailService emailService;

    @Autowired
    public PaymentController(OrderService orderService, YooKassaService yooKassaService, TicketEmailService emailService) {
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
            orderService.saveOrderToDatabase(order);

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
            if (!"succeeded".equals(paymentStatus)) { // ВАЖНО!
                model.addAttribute("message", "Ошибка оплаты, платеж не прошел. Попробуйте ещё раз");
                return "fragments/paymentFragments/errorFragment :: error";
            }

            TicketOrder order = orderService.getOrderByIdFromDatabase(cartId);
            if (order == null) {
                logger.warn("Заказ не найден с cartId: {}", cartId);
                model.addAttribute("message", "Заказ не найден");
                return "fragments/paymentFragments/errorFragment :: error";
            }

            if (order.getStatus() == OrderStatus.PAID && order.isEmailSent()) {
                logger.info("Заказ {} уже обработан (оплачен и письмо отправлено)", cartId);
                return "redirect:/order-success";
            }

            // Лимит попыток отправки письма
            if (order.getEmailRetryCount() > 3) {
                logger.warn("Превышен лимит попыток отправки письма для заказа {}", cartId);
                model.addAttribute("message", "Техническая ошибка. Попробуйте позже.");
                return "fragments/paymentFragments/errorFragment :: error";
            }



            order.setStatus(OrderStatus.PAID);
            orderService.saveOrderToDatabase(order);
            if (!order.isEmailSent()) {
                try {
                    emailService.sendTicketsEmail(order);
                    order.setEmailSent(true);
                    order.setEmailRetryCount(0);
                } catch (Exception e) {
                    order.setEmailRetryCount(order.getEmailRetryCount() + 1);
                    logger.error("Ошибка отправки письма для заказа {}: {}", cartId, e.getMessage());
                    // Сохраняем заказ в БД, чтобы не потерять изменения
                    orderService.saveOrderToDatabase(order);
                    model.addAttribute("message", "Не удалось отправить письмо. Попробуйте ещё раз.");
                    return "fragments/paymentFragments/errorFragment :: error";
                }
            }
            orderService.clearOrderFromRedis(cartId);
            logger.info("Заказ {} успешно оплачен и письмо отправлено", cartId);
            return "redirect:/order-success";

        } catch (JsonProcessingException e) {
            logger.warn("Ошибка метода /finalize-order: {}", e.getMessage());
            model.addAttribute("message", "Техническая ошибка. Попробуйте позже.");
            return "fragments/paymentFragments/errorFragment :: error";
        }
    }

}
