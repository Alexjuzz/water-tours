package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.telegram.TelegramLinkService;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class CorsPreflightTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean OrderCreationService orderCreation;
    @MockitoBean TelegramLinkService telegramLinkService;

    @Test
    void preflightForOrdersCreationAllowsTheProductionOriginAndTheCredentialHeaders() throws Exception {
        mvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "https://water-tours.ru")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key, Idempotency-Secret, X-Order-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://water-tours.ru"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("Idempotency-Key")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("Idempotency-Secret")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("X-Order-Token")));
    }

    /** L-9: developer origins are no longer part of the production allowlist. */
    @Test
    void preflightFromADeveloperOriginIsRefusedByDefault() throws Exception {
        mvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
