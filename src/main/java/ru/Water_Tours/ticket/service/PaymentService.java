package ru.Water_Tours.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.PaymentProvider;
import ru.Water_Tours.enums.PaymentStatus;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.model.payment.PaymentStartResponse;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RestClient yookassaClient;
    private final String baseUrl;

    public PaymentService(OrderRepository orderRepository,
                          PaymentRepository paymentRepository,
                          @Value("${yookassa.shopId}") String shopId,
                          @Value("${yookassa.secretKey}") String secretKey,
                          @Value("${app.base-url}") String baseUrl) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.baseUrl = baseUrl;

        this.yookassaClient = RestClient.builder()
                .baseUrl("https://api.yookassa.ru/v3")
                .defaultHeaders(headers -> headers.setBasicAuth(shopId, secretKey))
                .build();
    }

    @Transactional
    public PaymentStartResponse startPayment(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.DRAFT) {
            throw new IllegalStateException("Order must be in DRAFT to start payment. Current status=" + order.getStatus());
        }

        // 1. Создаём локальный платёж
        Payment payment = new Payment();
        payment.setProvider(PaymentProvider.YOOKASSA);
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment); // получаем id для Idempotence-Key

        // 2. Создаём платёж в ЮKassa
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("amount", Map.of(
                    "value", order.getTotalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    "currency", "RUB"
            ));
            requestBody.put("confirmation", Map.of(
                    "type", "redirect",
                    "return_url", baseUrl + "/api/v1/orders/" + orderId + "/pay/return"
            ));
            requestBody.put("capture", true);
            requestBody.put("description", "Оплата заказа Water Tours " + orderId);
            requestBody.put("metadata", Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", payment.getId().toString()
            ));

            Map<String, Object> response = yookassaClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotence-Key", payment.getId().toString())
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("id") == null) {
                throw new IllegalStateException("YooKassa returned empty response");
            }

            String providerPaymentId = (String) response.get("id");
            String status = (String) response.get("status");

            payment.setProviderPaymentId(providerPaymentId);
            payment.setStatus(mapYookassaStatus(status));

            @SuppressWarnings("unchecked")
            Map<String, Object> confirmation = (Map<String, Object>) response.get("confirmation");
            String paymentUrl = confirmation != null ? (String) confirmation.get("confirmation_url") : null;

            if (paymentUrl == null) {
                throw new IllegalStateException("YooKassa did not return confirmation_url");
            }

            paymentRepository.save(payment);

            order.setStatus(OrderStatus.PENDING_PAYMENT);
            orderRepository.save(order);

            return new PaymentStartResponse(
                    payment.getId(),
                    order.getId(),
                    payment.getStatus(),
                    payment.getAmount(),
                    paymentUrl
            );

        } catch (RestClientResponseException e) {
            log.error("YooKassa create payment failed: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            // Откатываем транзакцию — Payment и Order не должны остаться в PENDING
            throw new IllegalStateException("Failed to create payment in YooKassa: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Unexpected error while creating YooKassa payment", e);
            throw new IllegalStateException("Failed to create payment in YooKassa", e);
        }
    }

    /**
     * Безопасная обработка webhook.
     * НИКОГДА не доверяем статусу из тела запроса.
     * Всегда запрашиваем актуальный статус через API ЮKassa.
     */
    @Transactional
    public Payment handleWebhook(WebhookRequestDTO request) {
        if (request == null || request.getObject() == null || request.getObject().getId() == null) {
            log.warn("Webhook received with empty object.id — ignored");
            return null;
        }

        String providerPaymentId = request.getObject().getId();

        Payment payment =  paymentRepository.findByProviderPaymentId(providerPaymentId).orElse(null);
        if (payment == null) {
            log.warn("Webhook for unknown providerPaymentId={} — ignored", providerPaymentId);
            return null; // всегда отвечаем 200, ничего не меняем
        }

        // Уже финальный статус — ничего не делаем
        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.CANCELED) {
            return payment;
        }

        Order order = payment.getOrder();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return payment;
        }

        // === Главная защита: запрашиваем реальный статус у ЮKassa ===
        String realStatus;
        try {
            realStatus = fetchPaymentStatusFromProvider(providerPaymentId);
        } catch (Exception e) {
            log.error("Failed to fetch payment status from YooKassa for providerPaymentId={}", providerPaymentId, e);
            // Не меняем ничего, просто логируем. ЮKassa потом ретрайнет webhook.
            return payment;
        }

        PaymentStatus newStatus = mapYookassaStatus(realStatus);

        switch (newStatus) {
            case SUCCEEDED -> {
                payment.setSucceededAt(Instant.now());
                payment.setStatus(PaymentStatus.SUCCEEDED);
                order.setStatus(OrderStatus.PAID);
                order.setPaidAt(Instant.now());
                log.info("Payment SUCCEEDED via webhook. orderId={}, paymentId={}, providerPaymentId={}",
                        order.getId(), payment.getId(), providerPaymentId);
            }
            case CANCELED -> {
                payment.setStatus(PaymentStatus.CANCELED);
                order.setStatus(OrderStatus.CANCELLED);
                log.info("Payment CANCELED via webhook. orderId={}, paymentId={}, providerPaymentId={}",
                        order.getId(), payment.getId(), providerPaymentId);
            }
            default -> {
                // pending / waiting_for_capture — ничего не делаем
                log.debug("Webhook received non-final status={} for providerPaymentId={}", realStatus, providerPaymentId);
                return payment;
            }
        }

        paymentRepository.save(payment);
        orderRepository.save(order);
        return payment;
    }

    /**
     * Синхронный запрос актуального статуса платежа в ЮKassa.
     */
    private String fetchPaymentStatusFromProvider(String providerPaymentId) {
        Map<String, Object> response = yookassaClient.get()
                .uri("/payments/{id}", providerPaymentId)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("status") == null) {
            throw new IllegalStateException("Empty response from YooKassa for payment " + providerPaymentId);
        }
        return (String) response.get("status");
    }

    private PaymentStatus mapYookassaStatus(String yookassaStatus) {
        if (yookassaStatus == null) return PaymentStatus.PENDING;
        return switch (yookassaStatus.toLowerCase()) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "canceled" -> PaymentStatus.CANCELED;
            case "pending", "waiting_for_capture" -> PaymentStatus.PENDING;
            default -> PaymentStatus.PENDING;
        };
    }
}