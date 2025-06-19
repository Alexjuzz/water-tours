package com.watertours.project.service.paymentService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watertours.project.enums.OrderStatus;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.interfaces.email.TicketEmailService;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.service.emailService.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;

// TODO: Вынести отправку email с билетами из finalize-order и webhook
// TODO: Сделать отдельный сервис для повторных попыток отправки email по оплаченным заказам (PAID, emailSent=false)
// TODO: Добавить endpoint для ручного запроса отправки письма пользователем (с фронта)

@RestController
public class YooKassaWebhookController {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TicketEmailService emailService;
    private final OrderService orderService;
    private final Logger logger = org.slf4j.LoggerFactory.getLogger(YooKassaWebhookController.class);

    @Autowired
    public YooKassaWebhookController(OrderService orderService, TicketEmailService emailService) {
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

            // 1. Проверяем что пришёл нужный статус успешного платежа
            if ("payment.succeeded".equals(eventType) && "succeeded".equals(status)) {
                TicketOrder order = orderService.getOrderByIdFromDatabase(cartId);
                if (order == null) {
                    logger.error("Заказ с cartId={} не найден!", cartId);
                    // ЮKassa требует HTTP 200 — чтобы не повторяли отправку!
                    return ResponseEntity.ok("Order not found");
                }

                // 2. Если заказ уже оплачен и письмо отправлено — ничего делать не надо
                if (order.getStatus() == OrderStatus.PAID && order.isEmailSent()) {
                    logger.info("Заказ {} уже обработан (оплачен и письмо отправлено)", cartId);
                    return ResponseEntity.ok("Already processed");
                }



                // 4. Всегда отмечаем заказ как оплаченный, если раньше не был
                order.setStatus(OrderStatus.PAID);
                orderService.saveOrderToDatabase(order); // сохраняем все изменения!
                // 3. Если письмо ещё не отправляли и не превышен лимит попыток — пробуем отправить
                emailService.sendTicketsEmail(order);
                // 5. Очищаем временные данные, если они были
                orderService.clearOrderFromRedis(cartId);

                return ResponseEntity.ok("Processed");
            }

            // 6. Если неудачный статус — просто логируем
            logger.warn("Webhook: событие или статус не обрабатывается: event={}, status={}, cartId={}", eventType, status, cartId);

        } catch (Exception e) {
            logger.error("Ошибка в обработке webhook: {}", e.getMessage());
            // Не пугай ЮKassa ошибками, всё равно верни 200 OK
            return ResponseEntity.ok("Error processing webhook");
        }
        return ResponseEntity.ok("Webhook ignored");
    }


}
