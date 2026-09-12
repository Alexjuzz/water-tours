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
import ru.Water_Tours.ticket.service.PricingService;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = PricingController.class, properties = {
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Import({SecurityConfig.class, LoginAttemptService.class})
class PricingControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PricingService pricingService;

    @Test
    void currentPricesAreReadableWithoutAuthentication() throws Exception {
        PriceVersion version = new PriceVersion();
        version.setVersionNumber(3);
        version.setAdultPrice(new BigDecimal("1500.00"));
        version.setChildPrice(new BigDecimal("800.00"));
        version.setBenefitPrice(new BigDecimal("1020.00"));
        version.setBoatPrice30(new BigDecimal("3500.00"));
        version.setBoatPrice60(new BigDecimal("6000.00"));
        version.setBoatPrice90(new BigDecimal("9000.00"));
        version.setBoatPrice120(new BigDecimal("11000.00"));
        when(pricingService.getCurrent()).thenReturn(version);

        mvc.perform(get("/api/v1/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.tickets.ADULT").value(1500.00))
                .andExpect(jsonPath("$.boatRentalByDurationMinutes.30").value(3500.00));
    }
}
