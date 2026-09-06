package ru.Water_Tours.ticket.ticketController;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import ru.Water_Tours.component.TicketProperties;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.TestPaymentResponse;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.LocalCheckoutService;
import ru.Water_Tours.ticket.service.TicketService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Profile("local-checkout")
public class LocalCheckoutController {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final LocalCheckoutService checkoutService;
    private final TicketService ticketService;
    /**
     * Explicit allowlist for non-loopback callers, e.g. the Docker Desktop bridge gateway
     * address when this backend runs in a container and the WordPress bridge calls it via
     * the published host port. Empty by default; only takes effect under local-checkout.
     */
    private final Set<String> trustedRemoteAddresses;

    public LocalCheckoutController(LocalCheckoutService checkoutService, TicketService ticketService,
            @Value("${local-checkout.trusted-remote-addresses:}") String trustedRemoteAddresses) {
        this.checkoutService = checkoutService;
        this.ticketService = ticketService;
        this.trustedRemoteAddresses = Arrays.stream(trustedRemoteAddresses.split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @GetMapping(value = "/checkout.html", produces = MediaType.TEXT_HTML_VALUE)
    public String checkoutPage() throws java.io.IOException {
        return StreamUtils.copyToString(new ClassPathResource("local/checkout.html").getInputStream(), StandardCharsets.UTF_8);
    }

    @GetMapping("/api/v1/local-checkout/catalog")
    public Map<TicketType, BigDecimal> catalog() {
        Map<TicketType, BigDecimal> catalog = new LinkedHashMap<>();
        for (TicketType type : TicketType.values()) {
            catalog.put(type, TicketProperties.getPriceByType(type));
        }
        return catalog;
    }

    @PostMapping("/api/v1/orders/{orderId}/test-pay")
    public ResponseEntity<TestPaymentResponse> testPay(
            @PathVariable UUID orderId,
            @RequestParam UUID accessToken,
            HttpServletRequest request) {

        requireLoopback(request);
        checkoutService.confirmTestPayment(orderId, accessToken);
        List<TicketResponse> tickets = ticketService.issueTickets(orderId);
        Order order = checkoutService.getOwnedOrder(orderId, accessToken);
        return ResponseEntity.ok(toResponse(order, tickets, accessToken));
    }

    @GetMapping("/api/v1/orders/{orderId}/test-pay")
    public ResponseEntity<TestPaymentResponse> testPayStatus(
            @PathVariable UUID orderId,
            @RequestParam UUID accessToken,
            HttpServletRequest request) {

        requireLoopback(request);
        Order order = checkoutService.getOwnedOrder(orderId, accessToken);
        List<TicketResponse> tickets = ticketService.getTickets(orderId);
        return ResponseEntity.ok(toResponse(order, tickets, accessToken));
    }

    private TestPaymentResponse toResponse(Order order, List<TicketResponse> tickets, UUID accessToken) {
        String pdfUrl = "/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + accessToken;
        return new TestPaymentResponse(
                order.getId(),
                order.getStatus(),
                Boolean.TRUE.equals(order.getTestPaid()),
                order.getPaidAt(),
                tickets,
                pdfUrl
        );
    }

    private void requireLoopback(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!LOOPBACK_ADDRESSES.contains(remoteAddr) && !trustedRemoteAddresses.contains(remoteAddr)) {
            throw new AccessDeniedException("Test payment is only allowed from localhost");
        }
    }
}
