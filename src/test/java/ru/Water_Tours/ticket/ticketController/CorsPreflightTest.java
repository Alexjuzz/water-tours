package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.idempotency.IdempotencyService;
import ru.Water_Tours.ticket.service.*;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import(SecurityConfig.class)
class CorsPreflightTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean IdempotencyService<UUID> idempotency;

    @Test
    void preflightForOrdersCreationAllowsWordpressOriginAndIdempotencyKey() throws Exception {
        mvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "http://water-tours.local")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://water-tours.local"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("Idempotency-Key")));
    }
}
