package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.service.PdfTicketService;
import ru.Water_Tours.ticket.service.StaffTestEmailGuard;
import ru.Water_Tours.ticket.service.StaffTestOrderService;
import ru.Water_Tours.ticket.service.TicketService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = StaffTestOrderController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class StaffTestOrderControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean StaffTestOrderService testOrders;
    @MockitoBean StaffTestEmailGuard emailGuard;
    @MockitoBean TicketService ticketService;
    @MockitoBean PdfTicketService pdfTicketService;

    private Order stubCreatedOrder() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        return order;
    }

    @Test
    void createAndSendIssuesTheTicketThenMailsTheCheckedAddress() throws Exception {
        Order order = stubCreatedOrder();
        when(emailGuard.requireSendableAddress(" owner@example.ru ")).thenReturn("owner@example.ru");
        when(testOrders.createIssuedTestOrder("staff", false, "owner@example.ru")).thenReturn(order);

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("action", "create-and-send").param("email", " owner@example.ru "))
                .andExpect(status().is3xxRedirection());

        InOrder inOrder = inOrder(emailGuard, testOrders);
        inOrder.verify(emailGuard).requireSendableAddress(" owner@example.ru ");
        inOrder.verify(emailGuard).reserve("staff", "owner@example.ru");
        inOrder.verify(testOrders).createIssuedTestOrder("staff", false, "owner@example.ru");
        inOrder.verify(testOrders).sendTestEmail(order.getId(), "staff");
    }

    @Test
    void aRejectedAddressIssuesNoTicketAndSendsNothing() throws Exception {
        when(emailGuard.requireSendableAddress(any()))
                .thenThrow(new IllegalArgumentException("Укажите email, на который отправить тестовое письмо."));

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("action", "create-and-send").param("email", ""))
                .andExpect(status().is3xxRedirection());

        verifyNoInteractions(testOrders);
        verify(emailGuard, never()).reserve(any(), any());
    }

    @Test
    void aRepeatedClickWithinTheCooldownSendsNothing() throws Exception {
        when(emailGuard.requireSendableAddress(any())).thenReturn("owner@example.ru");
        doThrow(new IllegalStateException("Повторить можно через 42 с."))
                .when(emailGuard).reserve("staff", "owner@example.ru");

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("action", "create-and-send").param("email", "owner@example.ru"))
                .andExpect(status().is3xxRedirection());

        verifyNoInteractions(testOrders);
    }

    @Test
    void aRefusedSendStillReportsTheIssuedOrder() throws Exception {
        Order order = stubCreatedOrder();
        when(emailGuard.requireSendableAddress(any())).thenReturn("owner@example.ru");
        when(testOrders.createIssuedTestOrder("staff", false, "owner@example.ru")).thenReturn(order);
        doThrow(new RuntimeException("SMTP unavailable")).when(testOrders).sendTestEmail(order.getId(), "staff");

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("action", "create-and-send").param("email", "owner@example.ru"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/test-order?error=*"));
    }

    @Test
    void theDefaultButtonNeverSendsMail() throws Exception {
        when(testOrders.createIssuedTestOrder("staff", false, null)).thenReturn(stubCreatedOrder());

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("action", "create"))
                .andExpect(status().is3xxRedirection());

        verify(testOrders, never()).sendTestEmail(any(), any());
        verifyNoInteractions(emailGuard);
    }

    @Test
    void anonymousCannotReachThePageCreateOrPdf() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/staff/test-order")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/test-order").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/staff/test-order/{id}/tickets.pdf", id)).andExpect(status().is3xxRedirection());
        verifyNoInteractions(testOrders, pdfTicketService);
    }

    @Test
    void staffSeesThePageWithoutAnyAccessTokenInIt() throws Exception {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setAccessToken(UUID.randomUUID());
        order.setTestPaid(true);
        when(testOrders.recentTestOrders()).thenReturn(List.of(order));
        when(ticketService.getTickets(order.getId())).thenReturn(List.of());

        mvc.perform(get("/staff/test-order").with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(order.getAccessToken().toString()))));
    }

    @Test
    void creatingATestOrderRequiresCsrf() throws Exception {
        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(testOrders);
    }

    @Test
    void staffCanCreateATestOrder() throws Exception {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        when(testOrders.createIssuedTestOrder("staff", false, null)).thenReturn(order);

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(testOrders).createIssuedTestOrder("staff", false, null);
    }

    @Test
    void staffCanCreateAPrivateBoatTestOrderWithAnAddress() throws Exception {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        when(testOrders.createIssuedTestOrder("staff", true, "owner@example.org")).thenReturn(order);

        mvc.perform(post("/staff/test-order").with(user("staff").roles("STAFF")).with(csrf())
                        .param("flow", "boat").param("email", "owner@example.org"))
                .andExpect(status().is3xxRedirection());

        verify(testOrders).createIssuedTestOrder("staff", true, "owner@example.org");
    }

    @Test
    void sendingATestEmailIsStaffOnlyAndNeedsCsrf() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/staff/test-order/{id}/send", id).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/test-order/{id}/send", id).with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
        verify(testOrders, never()).sendTestEmail(any(), any());

        mvc.perform(post("/staff/test-order/{id}/send", id).with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(testOrders).sendTestEmail(id, "staff");
    }

    @Test
    void pdfIsServedOnlyAfterTheOrderIsConfirmedToBeATestOrder() throws Exception {
        UUID id = UUID.randomUUID();
        when(pdfTicketService.buildTicketsPdfByOrderId(eq(id), any())).thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/staff/test-order/{id}/tickets.pdf", id).with(user("staff").roles("STAFF")))
                .andExpect(status().isOk());

        verify(testOrders).requireTestOrder(id);
    }

    @Test
    void pdfIsRefusedForAnOrderThatIsNotATestOrder() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("Order " + id + " is not a test order"))
                .when(testOrders).requireTestOrder(id);

        mvc.perform(get("/staff/test-order/{id}/tickets.pdf", id).with(user("staff").roles("STAFF")))
                .andExpect(status().isConflict());

        verifyNoInteractions(pdfTicketService);
    }
}
