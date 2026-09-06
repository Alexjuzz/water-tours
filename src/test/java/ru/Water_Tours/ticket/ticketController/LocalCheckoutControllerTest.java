package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.service.LocalCheckoutService;
import ru.Water_Tours.ticket.service.TicketService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = LocalCheckoutController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "local-checkout.trusted-remote-addresses=172.28.0.1"
})
@ActiveProfiles("local-checkout")
@Import(SecurityConfig.class)
class LocalCheckoutControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean LocalCheckoutService checkoutService;
    @MockitoBean TicketService ticketService;

    private Order testPaidOrder(UUID id, Instant paidAt) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderStatus.PAID);
        order.setTestPaid(true);
        order.setPaidAt(paidAt);
        return order;
    }

    @Test
    void missingAccessTokenIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/orders/{id}/test-pay", id)).andExpect(status().isBadRequest());
        verifyNoInteractions(checkoutService, ticketService);
    }

    @Test
    void invalidAccessTokenIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(checkoutService.confirmTestPayment(id, token)).thenThrow(new AccessDeniedException("invalid"));

        mvc.perform(post("/api/v1/orders/{id}/test-pay", id).param("accessToken", token.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ticketService);
    }

    @Test
    void nonDraftOrderIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(checkoutService.confirmTestPayment(id, token))
                .thenThrow(new IllegalStateException("Order already has a real payment"));

        mvc.perform(post("/api/v1/orders/{id}/test-pay", id).param("accessToken", token.toString()))
                .andExpect(status().isConflict());
        verifyNoInteractions(ticketService);
    }

    @Test
    void repeatedConfirmationIsIdempotentAndPreservesPaidAt() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant paidAt = Instant.parse("2026-01-01T00:00:00Z");
        Order order = testPaidOrder(id, paidAt);

        when(checkoutService.confirmTestPayment(id, token)).thenReturn(order);
        when(checkoutService.getOwnedOrder(id, token)).thenReturn(order);
        when(ticketService.issueTickets(id)).thenReturn(List.of());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/orders/{id}/test-pay", id).param("accessToken", token.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.testPaid").value(true))
                    .andExpect(jsonPath("$.paidAt").value("2026-01-01T00:00:00Z"));
        }
        verify(checkoutService, times(2)).confirmTestPayment(id, token);
        verify(ticketService, times(2)).issueTickets(id);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void untrustedRemoteAddressIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        mvc.perform(post("/api/v1/orders/{id}/test-pay", id)
                        .param("accessToken", token.toString())
                        .with(remoteAddr("203.0.113.5")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(checkoutService, ticketService);
    }

    @Test
    void configuredDockerGatewayAddressIsTrusted() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant paidAt = Instant.parse("2026-01-01T00:00:00Z");
        Order order = testPaidOrder(id, paidAt);

        when(checkoutService.confirmTestPayment(id, token)).thenReturn(order);
        when(checkoutService.getOwnedOrder(id, token)).thenReturn(order);
        when(ticketService.issueTickets(id)).thenReturn(List.of());

        mvc.perform(post("/api/v1/orders/{id}/test-pay", id)
                        .param("accessToken", token.toString())
                        .with(remoteAddr("172.28.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void statusEndpointHasNoSideEffects() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Order order = testPaidOrder(id, Instant.now());
        when(checkoutService.getOwnedOrder(id, token)).thenReturn(order);
        when(ticketService.getTickets(id)).thenReturn(List.of());

        mvc.perform(get("/api/v1/orders/{id}/test-pay", id).param("accessToken", token.toString()))
                .andExpect(status().isOk());
        verify(ticketService, never()).issueTickets(any());
        verify(checkoutService, never()).confirmTestPayment(any(), any());
    }
}
