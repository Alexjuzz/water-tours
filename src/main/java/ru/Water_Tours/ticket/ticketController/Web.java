package ru.Water_Tours.ticket.ticketController;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.Water_Tours.ticket.idempotency.ResolveResult;
import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.idempotency.IdempotencyService;
import ru.Water_Tours.ticket.model.order.OrderResponse;
import ru.Water_Tours.ticket.model.payment.PaymentStartResponse;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
public class Web {
    private final OrderService orderService;
    private final TicketService ticketService;
    private final IdempotencyService<UUID> idempotencyService;
    private final PaymentService paymentService;
    private final PdfTicketService pdfTicketService;
    private final String baseUrl;
    private final TicketEmailService ticketEmailService;


    public Web(OrderService orderService,
               IdempotencyService<UUID> idempotencyService,
               PaymentService paymentService,
               TicketService ticketService,
               PdfTicketService pdfTicketService,
               TicketEmailService ticketEmailService,
               @Value("${app.base-url}") String baseUrl) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
        this.paymentService = paymentService;
        this.ticketService = ticketService;
        this.pdfTicketService = pdfTicketService;
        this.baseUrl = baseUrl;
        this.ticketEmailService = ticketEmailService;
    }

    @PostMapping("/api/v1/orders")
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody OrderRequestDTO orderRequestDTO) {

        if (idemKey == null || idemKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String idempotenceKey = idemKey.trim();

        String scope = "orders:create";
        Supplier<UUID> supplier = () -> orderService.createOrder(orderRequestDTO, idempotenceKey).getId();

        ResolveResult<UUID> resolve = idempotencyService.resolve(scope, idempotenceKey, supplier);
        UUID orderId = resolve.value();
        boolean reused = resolve.reused();

        OrderResponse response = orderService.getOrderResponse(orderId, idempotenceKey);

        if (reused) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }




    @PostMapping("/api/v1/orders/{orderId}/pay")
    public ResponseEntity<PaymentStartResponse> startPayment(@PathVariable UUID orderId, @RequestParam UUID accessToken) {
        orderService.checkAccess(orderId, accessToken);

        PaymentStartResponse response = paymentService.startPayment(orderId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/v1/payments/webhook")
    public ResponseEntity<Void> webhook(@RequestBody WebhookRequestDTO request) {
        paymentService.handleWebhook(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/orders/{orderId}/tickets/issue")
    public ResponseEntity<List<TicketResponse>> issueTickets(@PathVariable UUID orderId, @RequestParam UUID accessToken) {
        orderService.checkAccess(orderId, accessToken);
        List<TicketResponse> tickets = ticketService.issueTickets(orderId);
        return ResponseEntity.ok(tickets);
    }


    @PostMapping("/api/v1/tickets/{code}/redeem")
    public ResponseEntity<TicketResponse> redeemTicket(@PathVariable String code) {

        TicketResponse response = ticketService.redeemByCode(code);
        return ResponseEntity.ok(response);

    }


    @GetMapping("/api/v1/orders/{orderId}/tickets")
    public ResponseEntity<List<TicketResponse>> getTickets(
            @PathVariable UUID orderId,
            @RequestParam UUID accessToken) {

        orderService.checkAccess(orderId, accessToken);
        List<TicketResponse> tickets = ticketService.getTickets(orderId);
        return ResponseEntity.ok(tickets);
    }


    @GetMapping("/api/v1/orders/{orderId}/tickets/pdf")
    public ResponseEntity<byte[]> getTicketsPdf(
            @PathVariable UUID orderId,
            @RequestParam UUID accessToken) {

        orderService.checkAccess(orderId, accessToken);
        byte[] pdfBytes = pdfTicketService.buildTicketsPdfByOrderId(orderId, baseUrl);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"tickets-" + orderId + ".pdf\"")
                .body(pdfBytes);
    }


    @PostMapping("/api/v1/orders/{orderId}/tickets/email")
    public ResponseEntity<Void> sendTicketsEmail(
            @PathVariable UUID orderId,
            @RequestParam UUID accessToken) {

        orderService.checkAccess(orderId, accessToken);
        // TODO: rate-limit (не чаще 1 раза в минуту) — можно добавить позже
        ticketEmailService.sendTicketsPdf(orderId);
        return ResponseEntity.noContent().build();
    }
}