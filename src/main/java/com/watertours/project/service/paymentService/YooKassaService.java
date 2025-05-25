package com.watertours.project.service.paymentService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Service
public class YooKassaService {
    private final RestTemplate restTemplate;
    private final String shopId;
    private final String secretKey;

    @Autowired
    public YooKassaService(RestTemplate restTemplate,
                           @Value("${yookassa.shopId") String shopId,
                           @Value("${yookassa.secretKey}") String secretKey) {
        this.restTemplate = restTemplate;
        this.shopId = shopId;
        this.secretKey = secretKey;
    }

    /**
     * Создаёт платёж и возвращает URL, куда редиректить пользователя.
     *
     * @param cartId       ваш идентификатор заказа (корзины)
     * @param totalCents   сумма в копейках (например, 1598 = 15.98 RUB)
     * @param returnUrl    публичный URL (ngrok или продакшен) для возврата после оплаты
     * @return ссылка на форму YooKassa
     */

    public String createPayment(String cartId, int totalCents,String returnUrl){
        String url = "https://api.yookassa.ru/v3/payments";

        // Создаём заголовок Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(shopId, secretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Создаём тело запроса
        BigDecimal amount = BigDecimal.valueOf(totalCents).divide(BigDecimal.valueOf(100),2, RoundingMode.HALF_UP);

        Map<String, Object> body = new HashMap<>();
        body.put("amount", Map.of(
                "value", amount.toString(),
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
        if(response.getStatusCode() != HttpStatus.CREATED) {
            throw new RuntimeException("Ошибка создания платежа: " + response.getStatusCode());
        }

        //Достаем configuration_url из ответа
        Map<String, Object> responseBody = response.getBody();
        Map<String, Object> confirmation = (Map<String, Object>) responseBody.get("confirmation");
        return (String) confirmation.get("confirmation_url");

    }

}
