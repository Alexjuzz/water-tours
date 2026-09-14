package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The public form is anonymous and CSRF-exempt like the order API, so these tests pin what an
 * unauthenticated stranger can and cannot make it do.
 */
@WebMvcTest(value = SupportQuestionController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "support.enabled=true", "support.owner-telegram-chat-id=555"
})
@Import({SecurityConfig.class, LoginAttemptService.class, SupportProperties.class})
class SupportQuestionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SupportService supportService;
    @MockitoBean SupportRateLimiter rateLimiter;

    private static final String VALID =
            "{\"message\":\"Во сколько отправление в субботу?\",\"contact\":\"guest@example.ru\"}";

    private SupportInquiry accepted() {
        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setReference("WT-ABCD2345");
        return inquiry;
    }

    @Test
    void anAnonymousVisitorCanSubmitAndGetsOnlyTheirOwnReference() throws Exception {
        when(supportService.submitFromWebsite(anyString(), any(), any())).thenReturn(accepted());

        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.reference").value("WT-ABCD2345"));
    }

    @Test
    void theResponseNeverCarriesAnythingAboutAnOrderOrACustomer() throws Exception {
        when(supportService.submitFromWebsite(anyString(), any(), any())).thenReturn(accepted());

        String body = mvc.perform(post("/api/v1/support/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Оплатил, где билет?\",\"contact\":\"+7 999 123-45-67\","
                                + "\"orderReference\":\"2565b5d9-f8b7-46c3-ae83-26b55aa22b30\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The order id the visitor typed is stored as their claim and echoed back to nobody: a
        // stranger cannot use this endpoint to confirm an order or a phone number exists.
        assertThat(body).doesNotContain("2565b5d9").doesNotContain("accessToken").doesNotContain("email");
    }

    @Test
    void aFilledHoneypotIsRefusedAndNothingIsStored() throws Exception {
        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Реклама реклама реклама\",\"contact\":\"bot@example.ru\","
                                + "\"website\":\"http://spam.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accepted").value(false));

        verifyNoInteractions(supportService);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void tooShortAQuestionOrAnUnanswerableContactIsRefused() throws Exception {
        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"привет\",\"contact\":\"guest@example.ru\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Нормальный длинный вопрос\",\"contact\":\"не скажу\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(supportService);
    }

    @Test
    void theRateLimitRefusesWithoutStoringAnything() throws Exception {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(SupportRateLimiter.Decision.CONTACT);

        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));

        verifyNoInteractions(supportService);
    }

    @Test
    void theStatusEndpointExposesOnlyTheEnabledFlag() throws Exception {
        String body = mvc.perform(get("/api/v1/support/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"enabled\":true}");
    }
}
