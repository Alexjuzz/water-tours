package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.idempotency.IdempotencyConflictException;
import ru.Water_Tours.ticket.model.order.OrderResponse;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.telegram.TelegramLinkService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H-1 at the HTTP surface: what a replayed {@code Idempotency-Key} is allowed to put on the wire.
 */
@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class OrderCreateReplayTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private static final String BODY = "{\"email\":\"buyer@example.com\",\"tickets\":{\"ADULT\":2}}";

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean OrderCreationService orderCreation;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean TelegramLinkService telegramLinkService;

    private final UUID orderId = UUID.randomUUID();
    private final UUID accessToken = UUID.randomUUID();

    private void orderExists() {
        when(orders.getOrderResponse(eq(orderId), any())).thenReturn(new OrderResponse(
                orderId, KEY, "buyer@example.com", Instant.parse("2026-09-14T10:00:00Z"),
                OrderStatus.DRAFT, new BigDecimal("3000.00"), null, "+79000000000", accessToken, null));
        when(telegramLinkService.buildDeepLink(any(), any())).thenReturn("https://t.me/bot?start=x");
    }

    @Test
    void theCallerThatCreatedTheOrderStillRecoversItsTokenOnARetry() throws Exception {
        orderExists();
        when(orderCreation.createOrReuse(any(), eq(KEY), eq("owner-secret")))
                .thenReturn(new OrderCreationService.Result(orderId, true, true));

        mvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", KEY)
                        .header("Idempotency-Secret", "owner-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.accessToken").value(accessToken.toString()));
    }

    @Test
    void aReplayTheCallerCannotClaimIsRefusedAndDisclosesNothing() throws Exception {
        orderExists();
        when(orderCreation.createOrReuse(any(), eq(KEY), eq("stolen-key-no-secret")))
                .thenThrow(new IdempotencyConflictException(
                        "This Idempotency-Key already stands for a different order request."));

        mvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", KEY)
                        .header("Idempotency-Secret", "stolen-key-no-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    /**
     * Transition case: a record with no caller binding. The order is confirmed to exist and its
     * state is reported, but no credential leaves the server - which is exactly what made the
     * original replay a takeover.
     */
    @Test
    void anUnclaimableReplayIsAnsweredWithoutTokenEmailOrPhone() throws Exception {
        orderExists();
        when(orderCreation.createOrReuse(any(), eq(KEY), any()))
                .thenReturn(new OrderCreationService.Result(orderId, true, false));

        mvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.telegramDeepLink").doesNotExist());
    }

    @Test
    void creationStillRequiresAnIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }
}
