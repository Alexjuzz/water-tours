package ru.Water_Tours.ticket.model.Webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookRequestDTO {

    private String type;          // "notification"
    private String event;         // "payment.succeeded", "payment.canceled" и т.д.
    private ObjectData object;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObjectData {
        private String id;        // это providerPaymentId (ID платежа в ЮKassa)
        private String status;    // "succeeded", "canceled", "pending" и т.д.
        // остальные поля ЮKassa нам не нужны
    }
}