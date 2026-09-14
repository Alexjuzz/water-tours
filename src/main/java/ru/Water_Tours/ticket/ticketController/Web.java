package ru.Water_Tours.ticket.ticketController;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

import ru.Water_Tours.ticket.model.Webhook.WebhookRequestDTO;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.model.order.OrderResponse;
import ru.Water_Tours.ticket.model.payment.PaymentStartResponse;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.*;
import ru.Water_Tours.telegram.TelegramLinkService;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.UUID;

@RestController
public class Web {

    /**
     * Alternative to the {@code accessToken} query parameter. Query strings end up in reverse-proxy
     * access logs, browser history and any intermediary that records URLs, and this token is the
     * only authorisation an order has. The parameter still works: PDF links already handed to
     * customers and sent into Telegram chats carry it, and those must keep opening.
     */
    private static final String TOKEN_HEADER = "X-Order-Token";

    private final OrderService orderService;
    private final TicketService ticketService;
    private final OrderCreationService orderCreationService;
    private final PaymentService paymentService;
    private final PdfTicketService pdfTicketService;
    private final String baseUrl;
    private final TicketEmailService ticketEmailService;
    private final TelegramLinkService telegramLinkService;


    public Web(OrderService orderService,
               OrderCreationService orderCreationService,
               PaymentService paymentService,
               TicketService ticketService,
               PdfTicketService pdfTicketService,
               TicketEmailService ticketEmailService,
               TelegramLinkService telegramLinkService,
               @Value("${app.base-url}") String baseUrl) {
        this.orderService = orderService;
        this.orderCreationService = orderCreationService;
        this.paymentService = paymentService;
        this.ticketService = ticketService;
        this.pdfTicketService = pdfTicketService;
        this.baseUrl = baseUrl;
        this.ticketEmailService = ticketEmailService;
        this.telegramLinkService = telegramLinkService;
    }

    /**
     * @param idemKey      the retry key. Not a credential: it is a request header, readable by any
     *                     script on the page and by anything that logs headers.
     * @param callerSecret the caller's own random value, sent only by the caller that created the
     *                     order. This is what decides whether a replay is answered with the
     *                     order's access token. Optional, so a storefront build that predates it
     *                     still works - such a replay is answered without credentials instead.
     */
    @PostMapping("/api/v1/orders")
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @RequestHeader(value = "Idempotency-Secret", required = false) String callerSecret,
            @Valid @RequestBody OrderRequestDTO orderRequestDTO) {

        if (idemKey == null || idemKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String idempotenceKey = idemKey.trim();

        OrderCreationService.Result result =
                orderCreationService.createOrReuse(orderRequestDTO, idempotenceKey, callerSecret);

        OrderResponse base = orderService.getOrderResponse(result.orderId(), idempotenceKey);
        if (!result.callerAuthorized()) {
            // Replay of a record with no caller binding: confirm the order exists and what state
            // it is in, and stop there. No token, no e-mail, no phone, no deep link.
            return ResponseEntity.ok(new OrderResponse(base.id(), base.idempotencyKey(), null,
                    base.createdAt(), base.status(), base.totalAmount(), base.paidAt(), null, null, null));
        }

        String telegramDeepLink = telegramLinkService.buildDeepLink(result.orderId(), base.accessToken());
        OrderResponse response = new OrderResponse(base.id(), base.idempotencyKey(), base.email(), base.createdAt(),
                base.status(), base.totalAmount(), base.paidAt(), base.phone(), base.accessToken(), telegramDeepLink);

        if (result.reused()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }




    @PostMapping("/api/v1/orders/{orderId}/pay")
    public ResponseEntity<PaymentStartResponse> startPayment(
            @PathVariable UUID orderId,
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {
        UUID token = requireAccessToken(accessToken, tokenHeader);
        orderService.checkAccess(orderId, token);

        PaymentStartResponse response = paymentService.startPayment(orderId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/v1/payments/webhook")
    public ResponseEntity<Void> webhook(@RequestBody WebhookRequestDTO request) {
        paymentService.handleWebhook(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/orders/{orderId}/tickets/issue")
    public ResponseEntity<List<TicketResponse>> issueTickets(
            @PathVariable UUID orderId,
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {
        UUID token = requireAccessToken(accessToken, tokenHeader);
        orderService.checkAccess(orderId, token);
        List<TicketResponse> tickets = ticketService.issueTickets(orderId);
        return ResponseEntity.ok(tickets);
    }


    @PostMapping("/api/v1/tickets/{code}/redeem")
    public ResponseEntity<TicketResponse> redeemTicket(@PathVariable String code) {

        TicketResponse response = ticketService.redeemByCode(code);
        return ResponseEntity.ok(response);

    }


    @GetMapping("/api/v1/orders/{orderId}/status")
    public ResponseEntity<ru.Water_Tours.ticket.model.order.OrderStatusResponse> orderStatus(
            @PathVariable UUID orderId,
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {

        UUID token = requireAccessToken(accessToken, tokenHeader);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(orderService.getOrderStatus(orderId, token));
    }

    @GetMapping("/api/v1/orders/{orderId}/tickets")
    public ResponseEntity<List<TicketResponse>> getTickets(
            @PathVariable UUID orderId,
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {

        UUID token = requireAccessToken(accessToken, tokenHeader);
        orderService.checkAccess(orderId, token);
        List<TicketResponse> tickets = ticketService.getTickets(orderId);
        return ResponseEntity.ok(tickets);
    }


    @GetMapping("/api/v1/orders/{orderId}/tickets/pdf")
    public ResponseEntity<byte[]> getTicketsPdf(
            @PathVariable UUID orderId,
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {

        UUID token = requireAccessToken(accessToken, tokenHeader);
        orderService.checkAccess(orderId, token);
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
            @RequestParam(required = false) UUID accessToken,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader)
            throws MissingServletRequestParameterException {

        UUID token = requireAccessToken(accessToken, tokenHeader);
        orderService.checkAccess(orderId, token);
        // The access token is the customer's authorization here; the cooldown and the attempt cap
        // live in the service, so a repeated click or a reload cannot fan out into extra mail.
        ticketEmailService.resendTicketsPdf(orderId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Header wins over the query parameter when both are present. A malformed header reads as no
     * token at all: the caller gets the same 400 as an omitted one, which says nothing about
     * whether the order exists.
     */
    private static UUID requireAccessToken(UUID parameter, String header)
            throws MissingServletRequestParameterException {
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException ignored) {
                throw new MissingServletRequestParameterException("accessToken", "UUID");
            }
        }
        if (parameter == null) {
            throw new MissingServletRequestParameterException("accessToken", "UUID");
        }
        return parameter;
    }
}
