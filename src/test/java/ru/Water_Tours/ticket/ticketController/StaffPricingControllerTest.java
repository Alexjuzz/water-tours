package ru.Water_Tours.ticket.ticketController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.LoginAttemptService;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.service.PriceValues;
import ru.Water_Tours.ticket.service.PricingService;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = StaffPricingController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class StaffPricingControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PricingService pricingService;

    private static PriceVersion versionOf(int number) {
        PriceVersion version = new PriceVersion();
        version.setVersionNumber(number);
        version.setAdultPrice(new BigDecimal("1500.00"));
        version.setChildPrice(new BigDecimal("800.00"));
        version.setBenefitPrice(new BigDecimal("1020.00"));
        version.setBoatPrice30(new BigDecimal("3500.00"));
        version.setBoatPrice60(new BigDecimal("6000.00"));
        version.setBoatPrice90(new BigDecimal("9000.00"));
        version.setBoatPrice120(new BigDecimal("11000.00"));
        version.setPublishedBy("owner");
        return version;
    }

    @Test
    void anonymousCannotViewOrPublish() throws Exception {
        mvc.perform(get("/staff/prices")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/staff/prices").with(csrf())).andExpect(status().is3xxRedirection());
        verifyNoInteractions(pricingService);
    }

    @Test
    void staffCanViewCurrentPricesAndHistory() throws Exception {
        when(pricingService.getCurrent()).thenReturn(versionOf(2));
        when(pricingService.getHistory()).thenReturn(List.of(versionOf(2), versionOf(1)));

        mvc.perform(get("/staff/prices").with(user("staff").roles("STAFF")))
                .andExpect(status().isOk());
    }

    @Test
    void staffPublishRequiresCsrf() throws Exception {
        mvc.perform(post("/staff/prices").with(user("staff").roles("STAFF"))
                        .param("adultPrice", "1500").param("childPrice", "800").param("benefitPrice", "1020")
                        .param("boatPrice30", "3500").param("boatPrice60", "6000")
                        .param("boatPrice90", "9000").param("boatPrice120", "11000"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pricingService);
    }

    @Test
    void staffPublishSucceedsAndRedirectsWithMessage() throws Exception {
        when(pricingService.publish(any(PriceValues.class), eq("staff"))).thenReturn(versionOf(2));

        mvc.perform(post("/staff/prices").with(user("staff").roles("STAFF")).with(csrf())
                        .param("adultPrice", "1600").param("childPrice", "850").param("benefitPrice", "1100")
                        .param("boatPrice30", "3600").param("boatPrice60", "6100")
                        .param("boatPrice90", "9100").param("boatPrice120", "11100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/prices?msg=*"));

        verify(pricingService).publish(any(PriceValues.class), eq("staff"));
    }

    @Test
    void staffPublishValidationErrorRedirectsWithError() throws Exception {
        when(pricingService.publish(any(PriceValues.class), eq("staff")))
                .thenThrow(new IllegalArgumentException("adultPrice must not be negative"));

        mvc.perform(post("/staff/prices").with(user("staff").roles("STAFF")).with(csrf())
                        .param("adultPrice", "-1").param("childPrice", "800").param("benefitPrice", "1020")
                        .param("boatPrice30", "3500").param("boatPrice60", "6000")
                        .param("boatPrice90", "9000").param("boatPrice120", "11000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/prices?error=*"));
    }

    @Test
    void staffRollbackCallsServiceAndRedirects() throws Exception {
        when(pricingService.rollbackTo(1, "staff")).thenReturn(versionOf(3));

        mvc.perform(post("/staff/prices/rollback/1").with(user("staff").roles("STAFF")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/prices?msg=*"));

        verify(pricingService).rollbackTo(1, "staff");
    }
}
