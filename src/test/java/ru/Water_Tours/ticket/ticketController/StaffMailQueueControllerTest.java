package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.TicketEmailService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = StaffMailQueueController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "tickets.email-delivery.enabled=true",
        "tickets.email-delivery.deliver-paid-from=2026-09-12T18:00:00Z"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class StaffMailQueueControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderRepository orderRepository;
    @MockitoBean TicketEmailService ticketEmailService;

    private static Order queued(String email, Instant paidAt) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setEmail(email);
        order.setPaidAt(paidAt);
        order.setStatus(OrderStatus.PAID);
        order.setAccessToken(UUID.randomUUID());
        return order;
    }

    @Test
    void anonymousCannotViewOrSend() throws Exception {
        mvc.perform(get("/staff/mail-queue")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/mail-queue/{id}/send", UUID.randomUUID()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(ticketEmailService);
    }

    @Test
    void staffSeesMaskedAddressesAndNoAccessToken() throws Exception {
        Order held = queued("customer@example.org", Instant.parse("2026-09-07T10:00:00Z"));
        when(orderRepository.findOrdersAwaitingTicketEmail()).thenReturn(List.of(held));

        mvc.perform(get("/staff/mail-queue").with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("c***@e***.org")))
                .andExpect(content().string(not(containsString("customer@example.org"))))
                .andExpect(content().string(not(containsString(held.getAccessToken().toString()))))
                .andExpect(content().string(containsString("удержано")));
    }

    @Test
    void ordersPaidAfterTheStartDateAreShownAsAutomatic() throws Exception {
        when(orderRepository.findOrdersAwaitingTicketEmail())
                .thenReturn(List.of(queued("fresh@example.org", Instant.parse("2026-09-12T19:00:00Z"))));

        mvc.perform(get("/staff/mail-queue").with(user("staff").roles("STAFF")))
                .andExpect(content().string(containsString("уйдёт автоматически")));
    }

    @Test
    void sendingRequiresCsrf() throws Exception {
        mvc.perform(post("/staff/mail-queue/{id}/send", UUID.randomUUID()).with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ticketEmailService);
    }

    @Test
    void staffCanReleaseOneHeldEmailAtATime() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/staff/mail-queue/{id}/send", id).with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(ticketEmailService).sendTicketsPdf(id);
        verifyNoMoreInteractions(ticketEmailService);
    }

    @Test
    void maskingKeepsAddressesUnreadable() {
        assertThat(StaffMailQueueController.maskEmail("juzzleee@yandex.ru")).isEqualTo("j***@y***.ru");
        assertThat(StaffMailQueueController.maskEmail(null)).isEqualTo("—");
        assertThat(StaffMailQueueController.maskEmail("broken")).isEqualTo("***");
    }
}
