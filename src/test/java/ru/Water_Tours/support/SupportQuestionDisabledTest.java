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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With no owner recipient configured - the state this ships in - the form must be inert rather
 * than quietly collecting questions nobody will ever see.
 */
@WebMvcTest(value = SupportQuestionController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key",
        "support.owner-telegram-chat-id="
})
@Import({SecurityConfig.class, LoginAttemptService.class, SupportProperties.class})
class SupportQuestionDisabledTest {

    @Autowired MockMvc mvc;
    @MockitoBean SupportService supportService;
    @MockitoBean SupportRateLimiter rateLimiter;

    @Test
    void withoutAnOwnerChatIdNothingIsAcceptedAndTheSiteIsToldToHideTheButton() throws Exception {
        mvc.perform(get("/api/v1/support/status")).andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post("/api/v1/support/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Во сколько отправление?\",\"contact\":\"guest@example.ru\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.accepted").value(false));

        verifyNoInteractions(supportService);
    }
}
