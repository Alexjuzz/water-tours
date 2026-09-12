package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.TicketService;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = CheckController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class CheckControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TicketService ticketService;

    private static final String CODE = UUID.randomUUID().toString();

    @Test
    void anonymousCannotOpenCheckPage() throws Exception {
        mvc.perform(get("/t/{code}", CODE))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(ticketService);
    }

    @Test
    void customerRoleCannotOpenCheckPage() throws Exception {
        mvc.perform(get("/t/{code}", CODE).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ticketService);
    }

    @Test
    void staffCsrfIsRequiredToRedeem() throws Exception {
        mvc.perform(post("/t/{code}/redeem", CODE).with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ticketService);
    }

    @Test
    void staffRedeemsValidTicketThenSecondAttemptIsRejectedInRussian() throws Exception {
        when(ticketService.renderCheckPage(eq(CODE), any(), any()))
                .thenReturn("<html><body>{{errorMessage}}</body></html>");

        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), CODE, "buyer@example.com",
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now(),
                TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode(CODE))
                .thenReturn(redeemed)
                .thenThrow(new IllegalArgumentException("already used"));

        mvc.perform(post("/t/{code}/redeem", CODE).with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/t/" + CODE));

        mvc.perform(post("/t/{code}/redeem", CODE).with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/t/" + CODE + "?error=USED"));

        mvc.perform(get("/t/{code}", CODE).param("error", "USED").with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Этот билет уже был использован.")));

        verify(ticketService, times(2)).redeemByCode(CODE);
    }
}
