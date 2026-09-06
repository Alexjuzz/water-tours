package ru.Water_Tours.ticket.ticketController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.Water_Tours.security.SecurityConfig;
import ru.Water_Tours.ticket.service.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@WebMvcTest(value=LocalCheckoutController.class,properties={"staff.username=test","staff.password=test-only","staff.remember-me-key=test-only-key"})
@Import(SecurityConfig.class)
class LocalCheckoutDisabledTest {
 @Autowired MockMvc mvc;
 @MockitoBean LocalCheckoutService checkout;
 @MockitoBean TicketService tickets;
 @Test void localFeaturesAbsentWithoutProfile() throws Exception {
  mvc.perform(get("/checkout.html")).andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/local-checkout/catalog")).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/orders/00000000-0000-0000-0000-000000000001/test-pay")).andExpect(status().isNotFound());
 }
}
