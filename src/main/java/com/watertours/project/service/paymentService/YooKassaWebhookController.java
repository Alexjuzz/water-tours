package com.watertours.project.service.paymentService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.service.emailService.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;

@RestController
public class YooKassaWebhookController {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailService emailService;
    private final OrderService orderService;
    private final Logger logger = org.slf4j.LoggerFactory.getLogger(YooKassaWebhookController.class);

    @Autowired
    public YooKassaWebhookController(OrderService orderService, EmailService emailService) {
        this.emailService = emailService;
        this.orderService = orderService;
    }

    @PostMapping("/yookassa/webhook")
    public ResponseEntity<String> handleWebhookYooKassa(@RequestBody String payload) {
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            String eventType = jsonNode.path("event").asText();
            JsonNode objectNode = jsonNode.get("object");
            String status = objectNode.path("status").asText();
            String cartId = objectNode.path("metadata").path("cartId").asText();
            if ("payment.succeeded".equals(eventType) && "succeeded".equals(status)) {
                TicketOrder order = null;
                try {
                    order = orderService.getOrderById(cartId);
                    if (order == null || order.getStatus() == OrderStatus.PAID) {
                        logger.error("Вызов метода /yookassa/webhook, не найден заказ с cartId: {}. Или заказ был оплачен ранее", cartId);
                        return ResponseEntity.ok("Order not found");
                    }
                    emailService.sendTicketsEmail(order);
                    order.setStatus(OrderStatus.PAID);
                    orderService.saveOrderToDatabase(order);
                } catch (Exception e) {
                    logger.error("Вызов метода /yookassa/webhook, не удалось найти заказ: {}", cartId, e);
                    return ResponseEntity.ok("Order not found");
                }
            } else if ("payment.waiting_for_capture".equals(eventType) && "waiting_for_capture".equals(status)) {
                logger.info("Вызов метода /yookassa/webhook, заказ {} ожидает подтверждения", cartId);
            } else {
                logger.warn("Вызов метода /yookassa/webhook, не удалось обработать событие: {}, статус: {}", eventType, status);
            }
        }catch (Exception e) {
            logger.error("Ошибка при обработке вебхука YooKassa: {}", e.getMessage());
            return ResponseEntity.ok("Ошибка при обработке вебхука YooKassa");
        }

        return ResponseEntity.ok("Webhook received successfully");
    }
}
