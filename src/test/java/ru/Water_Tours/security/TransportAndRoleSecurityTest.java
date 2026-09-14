package ru.Water_Tours.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.ticket.ticketController.Web;
import ru.Water_Tours.telegram.TelegramLinkService;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M-1 and M-4. The transport headers the app emits, and who is allowed to move money or change
 * published prices.
 */
@WebMvcTest(value = Web.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "app.base-url=http://localhost:8080"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class TransportAndRoleSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orders;
    @MockitoBean OrderCreationService orderCreation;
    @MockitoBean PaymentService payments;
    @MockitoBean TicketService tickets;
    @MockitoBean PdfTicketService pdf;
    @MockitoBean TicketEmailService mail;
    @MockitoBean TelegramLinkService telegramLinkService;

    @Test
    void everyResponseCarriesAContentSecurityPolicyAndAReferrerPolicy() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("form-action 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("base-uri 'none'")))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                // Spring Security's existing defaults must survive the headers() customisation.
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /**
     * HSTS is emitted only for a request Tomcat reports as secure. Behind the TLS proxy the
     * RemoteIpValve makes that true; on plain local development it stays false, which is why the
     * header is not simply forced on.
     */
    @Test
    void hstsIsSentOverHttpsAndNotOverPlainHttp() throws Exception {
        mvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"));
        // includeSubDomains is deliberately absent by default: it cannot be withdrawn from a
        // browser for a year, and no live check confirmed every subdomain serves HTTPS.

        mvc.perform(get("/login"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void aStaffAccountCannotRefundOrPublishPrices() throws Exception {
        mvc.perform(get("/staff/refund").with(user("clerk").roles("STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/refund/" + java.util.UUID.randomUUID()).with(user("clerk").roles("STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/staff/prices").with(user("clerk").roles("STAFF")))
                .andExpect(status().isForbidden());
    }

    /**
     * The single configured account holds both roles, so the split takes nothing away from the
     * access that exists today. Only an explicitly configured STAFF-only account is restricted.
     */
    @Test
    void anOwnerAccountReachesTheOwnerOnlyPagesAndTheStaffOnlyOnes() throws Exception {
        mvc.perform(get("/staff/refund").with(user("owner").roles("STAFF", "OWNER")))
                .andExpect(status().isNotFound()); // routed past security; no controller in this slice
        mvc.perform(get("/staff/mail-queue").with(user("owner").roles("STAFF", "OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousStillReachesNeither() throws Exception {
        mvc.perform(get("/staff/refund"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/staff/prices"))
                .andExpect(status().is3xxRedirection());
    }
}
