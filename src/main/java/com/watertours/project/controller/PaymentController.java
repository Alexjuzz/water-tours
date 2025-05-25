package com.watertours.project.controller;

import com.watertours.project.enums.OrderStatus;
import com.watertours.project.service.OrderService;
import com.watertours.project.service.paymentService.YooKassaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PaymentController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private YooKassaService yooKassaService;

    @GetMapping("/payment")
    public String payment(@RequestParam String cartId) {
            int total = orderService.getOrder(cartId).getTotalAmount();
            String returnUrl = "https://8fb6-89-110-78-103.ngrok-free.app/finalize-order"; // Замените на ваш URL для возврата после оплаты
            String paymentUrl = yooKassaService.createPayment(cartId, total, returnUrl);
        return "redirect:" + paymentUrl;
    }

    @GetMapping("/finalize-order")
    public String finalizeOrd(@RequestParam String cartId, Model model) {
        // Здесь вы можете обработать успешную оплату, например, сохранить заказ в базе данных
        orderService.changeStatusOrder(orderService.getOrder(cartId), OrderStatus.PAID);
        model.addAttribute("message", "Оплата прошла успешно! Ваш заказ подтвержден.");
        return "redirect:/order-success"; // Перенаправление на страницу успеха
    }
}
