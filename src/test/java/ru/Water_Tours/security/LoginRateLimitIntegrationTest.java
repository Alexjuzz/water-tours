package ru.Water_Tours.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.ticket.ticketController.Web;
import ru.Water_Tours.telegram.TelegramLinkService;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import({SecurityConfig.class, LoginAttemptService.class, LoginAttemptListener.class})
class LoginRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired LoginAttemptService loginAttemptService;
    @MockitoBean OrderService orders;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean OrderCreationService orderCreation;
    @MockitoBean TelegramLinkService telegramLinkService;

    @BeforeEach
    void resetLoginAttempts() {
        loginAttemptService.reset("127.0.0.1");
        loginAttemptService.reset("203.0.113.9");
        loginAttemptService.reset("198.51.100.4");
    }

    @Test
    void blocksLoginAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(formLogin().user("test").password("wrong-password"))
                    .andExpect(status().is3xxRedirection());
        }

        mvc.perform(formLogin().user("test").password("wrong-password"))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * M-3. The throttle used to key on the reverse proxy's address, so every visitor shared one
     * counter and ten bad logins from anybody locked the console out for the owner too.
     */
    @Test
    void oneAddressExhaustingTheLimitDoesNotBlockAnother() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(loginFrom("203.0.113.9", "wrong-password"))
                    .andExpect(status().is3xxRedirection());
        }

        mvc.perform(loginFrom("203.0.113.9", "wrong-password"))
                .andExpect(status().isTooManyRequests());

        // The owner, on a different address, is unaffected.
        mvc.perform(loginFrom("198.51.100.4", "test-only"))
                .andExpect(status().is3xxRedirection());
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginFrom(String address, String password) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                .param("username", "test")
                .param("password", password)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                });
    }

    @Test
    void correctPasswordStillWorksBelowThreshold() throws Exception {
        mvc.perform(formLogin().user("test").password("test-only"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void successfulLoginLandsOnTheStaffHome() throws Exception {
        mvc.perform(formLogin().user("test").password("test-only"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/staff"));
    }
}
