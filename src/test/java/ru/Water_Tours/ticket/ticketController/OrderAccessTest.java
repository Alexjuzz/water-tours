package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.idempotency.IdempotencyService;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.telegram.TelegramLinkService;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import(SecurityConfig.class)
class OrderAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean IdempotencyService<UUID> idempotency;
    @MockitoBean TelegramLinkService telegramLinkService;

    @Test
    void missingTokenCannotStartPaymentOrIssueTickets() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/orders/{id}/pay", id)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders/{id}/tickets/issue", id)).andExpect(status().isBadRequest());
        verifyNoInteractions(payments, tickets);
    }

    @Test
    void wrongTokenCannotStartPaymentOrIssueTickets() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        doThrow(new AccessDeniedException("invalid")).when(orders).checkAccess(id, token);
        mvc.perform(post("/api/v1/orders/{id}/pay", id).param("accessToken", token.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/{id}/tickets/issue", id).param("accessToken", token.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments, tickets);
    }

    @Test
    void validTokenAllowsPaymentAndTicketIssue() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        mvc.perform(post("/api/v1/orders/{id}/pay", id).param("accessToken", token.toString()))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/orders/{id}/tickets/issue", id).param("accessToken", token.toString()))
                .andExpect(status().isOk());
        verify(orders, times(2)).checkAccess(id, token);
        verify(payments).startPayment(id);
        verify(tickets).issueTickets(id);
    }

    @Test
    void staffMustProvideCsrfToRedeemTicket() throws Exception {
        mvc.perform(post("/api/v1/tickets/code/redeem").with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tickets);
        mvc.perform(post("/api/v1/tickets/code/redeem").with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().isOk());
        verify(tickets).redeemByCode("code");
    }

    @Test
    void customerCannotRedeemEvenWithCsrf() throws Exception {
        mvc.perform(post("/api/v1/tickets/code/redeem").with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tickets);
    }
    @Test void pdfDownloadRequiresAccessAndDisablesCache() throws Exception {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String url = "/api/v1/orders/" + id + "/tickets/pdf";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(status().isBadRequest());
        doThrow(new AccessDeniedException("invalid")).when(orders).checkAccess(id,token);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("accessToken",token.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdf);
        doNothing().when(orders).checkAccess(id,token);
        byte[] expected = new byte[]{1,2,3};
        when(pdf.buildTicketsPdfByOrderId(id,"http://localhost:8080")).thenReturn(expected);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("accessToken",token.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(expected));
    }}