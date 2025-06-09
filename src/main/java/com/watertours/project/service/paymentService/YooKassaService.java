package com.watertours.project.service.paymentService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watertours.project.controller.PaymentController;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class YooKassaService {
    private final RestTemplate restTemplate;
    private final String shopId;
    private final String secretKey;
    private final ObjectMapper mapper;
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PaymentController.class); {
    };

    @Autowired
    public YooKassaService(RestTemplate restTemplate,
                           @Value("${yookassa.shopId}") String shopId,
                           @Value("${yookassa.secretKey}") String secretKey,
                           ObjectMapper mapper
                           ) {
        this.restTemplate = restTemplate;
        this.shopId = shopId;
        this.secretKey = secretKey;
        this.mapper = mapper;
    }

    /**
     * Создаёт платёж и возвращает URL, куда редиректить пользователя.
     *
     * @param cartId       ваш идентификатор заказа (корзины)
     * @param totalRubles   сумма в рублях, которую нужно оплатить
     * @param returnUrl    публичный URL (ngrok или продакшен) для возврата после оплаты
     * @return ссылка на форму YooKassa
     */

    public String createPayment(String cartId, int totalRubles,String returnUrl){
        String url = "https://api.yookassa.ru/v3/payments";

        // Создаём заголовок Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(shopId, secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        //Уникальный ключ Idempotency-key Для предотвращения повторного создания платежа
        String idempotencyKey = UUID.randomUUID().toString();
        headers.set("Idempotence-Key", idempotencyKey);

        // Создаём тело запроса
        BigDecimal amount = BigDecimal.valueOf(totalRubles).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> body = new HashMap<>();
        body.put("amount", Map.of(
                "value", amount,
                "currency", "RUB"
        ));
        body.put("capture", true);
        body.put("discription", "Оплата заказа WaterTours, cartId " + cartId);
        body.put("confirmation", Map.of(
                "type", "redirect",
                "return_url", returnUrl
        ));
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        // Отправляем POST запрос к YooKassa API
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, Map.class);

        // Проверяем статус ответа
        if(response.getStatusCode() != HttpStatus.CREATED && response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Ошибка создания платежа: " + response.getStatusCode());
        }

        //Достаем configuration_url из ответа
        Map<String, Object> responseBody = response.getBody();
        Map<String, Object> confirmation = (Map<String, Object>) responseBody.get("confirmation");
        return (String) confirmation.get("confirmation_url");

    }

    public String checkPaymentStatus(String paymentId) {
        String url = "https://api.yookassa.ru/v3/payments/" + paymentId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(shopId, secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);


        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = null;
                try {
                    root = mapper.readTree(response.getBody());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
                String status = root.get("status").asText();
                return status; // "succeeded", "pending", "canceled"

            } else {
                logger.error("Ошибка при вызове /checkPaymentStatus проверке статуса платежа: {}",
                        response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            logger.error("Ошибка при проверке статуса платежа в YooKassa: {}", e.getMessage());
            return null;
        }
    }
}
