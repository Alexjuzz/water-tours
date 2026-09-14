package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.OrderEmailCorrectionRepository;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.TicketRepository;
import ru.Water_Tours.ticket.service.StaffEmailCorrectionService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = StaffOrderSupportController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class StaffOrderSupportControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderRepository orderRepository;
    @MockitoBean TicketRepository ticketRepository;
    @MockitoBean OrderEmailCorrectionRepository correctionRepository;
    @MockitoBean StaffEmailCorrectionService correctionService;

    Order order;

    @BeforeEach
    void setup() {
        order = new Order();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        order.setEmail("buyer@example.org");
        order.setPhone("+7 (999) 123-45-67");
        order.setTotalAmount(new BigDecimal("1500.00"));
        order.setAccessToken(UUID.randomUUID());
        order.setCreatedAt(Instant.parse("2026-09-14T09:00:00Z"));
        order.setPaidAt(Instant.parse("2026-09-14T09:02:00Z"));
        order.setTicketIssuedAt(Instant.parse("2026-09-14T09:03:00Z"));

        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTicketStatus(TicketStatus.ISSUED);
        ticket.setTicketType(TicketType.ADULT);
        ticket.setCode("SECRET-TICKET-CODE");
        when(ticketRepository.findAllByOrderId(order.getId())).thenReturn(List.of(ticket));
        when(correctionRepository.findAllByOrderIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(correctionService.ineligibilityReason(any(), any())).thenReturn(Optional.empty());
        when(correctionService.maxPerOrder()).thenReturn(3);
        when(correctionService.cooldown()).thenReturn(Duration.ofSeconds(60));
    }

    @Test
    void anonymousReachesNeitherThePageNorTheCorrection() throws Exception {
        mvc.perform(get("/staff/order-support")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/order-support/{id}/email", order.getId()).with(csrf())
                        .param("newEmail", "a@b.co").param("confirmEmail", "a@b.co").param("reason", "опечатка"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(correctionService);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void theCorrectionRequiresACsrfToken() throws Exception {
        mvc.perform(post("/staff/order-support/{id}/email", order.getId())
                        .with(user("staff").roles("STAFF"))
                        .param("newEmail", "a@b.co").param("confirmEmail", "a@b.co").param("reason", "опечатка"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(correctionService);
    }

    @Test
    void anExactOrderIdFindsTheOrderAndNeverShowsTheAccessTokenOrTicketCode() throws Exception {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        mvc.perform(get("/staff/order-support").param("query", order.getId().toString())
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(order.getId().toString())))
                .andExpect(content().string(containsString("buyer@example.org")))
                .andExpect(content().string(not(containsString(order.getAccessToken().toString()))))
                .andExpect(content().string(not(containsString("SECRET-TICKET-CODE"))))
                .andExpect(content().string(containsString("Имя покупателя система не хранит")));
    }

    @Test
    void anExactPhoneIsLookedUpNormalisedAndAPartialOneIsRefused() throws Exception {
        when(orderRepository.findAllByNormalizedPhone(any())).thenReturn(List.of(order));

        mvc.perform(get("/staff/order-support").param("query", "8 999 123 45 67")
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(order.getId().toString())));
        verify(orderRepository).findAllByNormalizedPhone(
                argThat(digits -> digits.contains("79991234567") && digits.contains("89991234567")));

        mvc.perform(get("/staff/order-support").param("query", "9991")
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("целиком")));
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    void anExactEmailIsLookedUpAndAnUnknownOneFindsNothingRatherThanAList() throws Exception {
        when(orderRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc("buyer@example.org"))
                .thenReturn(List.of(order));

        mvc.perform(get("/staff/order-support").param("query", " Buyer@Example.ORG ")
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(order.getId().toString())));

        when(orderRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc("nobody@example.org"))
                .thenReturn(List.of());
        mvc.perform(get("/staff/order-support").param("query", "nobody@example.org")
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ничего не найдено")));
    }

    @Test
    void customerSuppliedTextIsEscaped() throws Exception {
        order.setEmail("<script>alert(1)</script>@example.org");
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        mvc.perform(get("/staff/order-support").param("query", order.getId().toString())
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void anIneligibleOrderShowsTheReasonInsteadOfTheForm() throws Exception {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(correctionService.ineligibilityReason(any(), any()))
                .thenReturn(Optional.of("Билет уже использован — повторная отправка недоступна."));

        mvc.perform(get("/staff/order-support").param("query", order.getId().toString())
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Билет уже использован")))
                .andExpect(content().string(not(containsString("name=\"confirmEmail\""))));
    }

    @Test
    void staffSeesTheFormWithConfirmationAndCsrfWhenTheOrderIsEligible() throws Exception {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        mvc.perform(get("/staff/order-support").param("query", order.getId().toString())
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"newEmail\"")))
                .andExpect(content().string(containsString("name=\"confirmEmail\"")))
                .andExpect(content().string(containsString("name=\"reason\"")))
                .andExpect(content().string(containsString("wtConfirmCorrection")))
                .andExpect(content().string(containsString("data-old-masked=\"b***@e***.org\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void aSuccessfulCorrectionReportsSmtpAcceptanceAndAFailedOneDoesNotClaimDelivery() throws Exception {
        when(correctionService.correctAndResend(any(), any(), any(), any(), any()))
                .thenReturn(new StaffEmailCorrectionService.Result(true, "Адрес исправлен, письмо принято (SMTP)."));

        mvc.perform(post("/staff/order-support/{id}/email", order.getId())
                        .with(user("staff").roles("STAFF")).with(csrf())
                        .param("newEmail", "fixed@example.org").param("confirmEmail", "fixed@example.org")
                        .param("reason", "опечатка").param("query", order.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern("/staff/order-support?query=*&msg=*"));

        when(correctionService.correctAndResend(any(), any(), any(), any(), any()))
                .thenReturn(new StaffEmailCorrectionService.Result(false, "Письмо отправить не удалось."));

        mvc.perform(post("/staff/order-support/{id}/email", order.getId())
                        .with(user("staff").roles("STAFF")).with(csrf())
                        .param("newEmail", "fixed@example.org").param("confirmEmail", "fixed@example.org")
                        .param("reason", "опечатка").param("query", order.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern("/staff/order-support?query=*&warn=*"));
    }

    @Test
    void aRefusedCorrectionShowsWhyAndSendsNothing() throws Exception {
        when(correctionService.correctAndResend(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Письмо по этому заказу уже отправляется."));

        mvc.perform(post("/staff/order-support/{id}/email", order.getId())
                        .with(user("staff").roles("STAFF")).with(csrf())
                        .param("newEmail", "fixed@example.org").param("confirmEmail", "fixed@example.org")
                        .param("reason", "опечатка"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern("/staff/order-support?error=*"));
    }
}
