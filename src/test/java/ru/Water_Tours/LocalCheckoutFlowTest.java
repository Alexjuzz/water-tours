package ru.Water_Tours;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=local-checkout,test",
        "yookassa.shopId=test", "yookassa.secretKey=test", "app.base-url=http://localhost:8080",
        "spring.mail.username=test@example.invalid", "spring.mail.password=test",
        "app.mail-from=test@example.invalid", "spring.jpa.show-sql=false",
        "management.health.mail.enabled=false", "payments.reconciliation.enabled=false",
        "tickets.issuance.enabled=false", "order.expiration-check-interval=86400000",
        "staff.username=test", "staff.password=test-only", "staff.remember-me-key=test-only-key"
})
@Testcontainers
@AutoConfigureMockMvc
class LocalCheckoutFlowTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OrderRepository orders;

    @Test
    void orderCreatedThenTestPaidExposesIssuedTicketsWithValidity() throws Exception {
        Instant before = Instant.now();

        String createBody = mvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"test@example.invalid","phoneNumber":"+10000000000","tickets":{"ADULT":2}}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createBody);
        UUID orderId = UUID.fromString(created.get("id").asText());
        UUID accessToken = UUID.fromString(created.get("accessToken").asText());
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");

        String payBody = mvc.perform(post("/api/v1/orders/{id}/test-pay", orderId)
                        .param("accessToken", accessToken.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paid = mapper.readTree(payBody);
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("testPaid").asBoolean()).isTrue();
        assertThat(paid.get("tickets")).hasSize(2);
        for (JsonNode ticket : paid.get("tickets")) {
            assertThat(UUID.fromString(ticket.get("id").asText())).isNotNull();
            Instant validTo = Instant.parse(ticket.get("validTo").asText());
            assertThat(validTo).isAfter(before.plusSeconds(71 * 3600)).isBefore(before.plusSeconds(73 * 3600));
        }
        assertThat(orders.findById(orderId).orElseThrow().getTestPaid()).isTrue();

        String statusBody = mvc.perform(get("/api/v1/orders/{id}/test-pay", orderId)
                        .param("accessToken", accessToken.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode statusJson = mapper.readTree(statusBody);
        assertThat(statusJson.get("tickets")).hasSize(2);
        assertThat(statusJson.get("id").asText()).isEqualTo(orderId.toString());

        mvc.perform(get("/api/v1/orders/{id}/test-pay", orderId).param("accessToken", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
