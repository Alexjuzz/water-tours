package ru.Water_Tours.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.BoatRouteType;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.BoatRentalRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.repository.PaymentRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Staff-only rehearsal of the paid-ticket path (task: safe production test flow).
 *
 * Deliberately narrow: it creates its own order, marks it test-paid without touching the payment
 * provider, and refuses to act on any order it did not create, so a real customer order can never
 * be flipped to paid from here. No refund path, no public endpoint, no local-checkout profile.
 *
 * Test orders never join automatic delivery - the delivery queue excludes test_paid orders - so
 * e-mail for them is always an explicit staff action against the address the staff member typed.
 * Their ticketsEmailedAt is left honest (null until something is actually sent).
 */
@Service
public class StaffTestOrderService {

    private static final Logger log = LoggerFactory.getLogger(StaffTestOrderService.class);

    public static final String TEST_EMAIL_DOMAIN = "@test.invalid";
    private static final String TEST_PHONE = "+70000000000";
    private static final int TEST_BOAT_MINUTES = 60;
    private static final int TEST_BOAT_GUESTS = 2;

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TicketService ticketService;
    private final TicketEmailService ticketEmailService;
    private final Clock clock;

    public StaffTestOrderService(OrderService orderService, OrderRepository orderRepository,
                                 PaymentRepository paymentRepository, TicketService ticketService,
                                 TicketEmailService ticketEmailService, Clock clock) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.ticketService = ticketService;
        this.ticketEmailService = ticketEmailService;
        this.clock = clock;
    }

    @Transactional
    public Order createIssuedTestOrder(String staffUsername, boolean privateBoat, String requestedEmail) {
        String email = normalizeEmail(requestedEmail);
        OrderRequestDTO request = privateBoat
                ? new OrderRequestDTO(email, TEST_PHONE, null,
                        new BoatRentalRequestDTO(TEST_BOAT_MINUTES, TEST_BOAT_GUESTS, BoatRouteType.ASSISTED, "Тестовый заказ"))
                : new OrderRequestDTO(email, TEST_PHONE, Map.of(TicketType.ADULT, 1));

        Order created = orderService.createOrder(request, "staff-test-" + UUID.randomUUID());

        Order order = orderRepository.findByIdForUpdate(created.getId()).orElseThrow();
        requireNoRealPayment(order);

        order.setStatus(OrderStatus.PAID);
        order.setTestPaid(true);
        order.setPaidAt(Instant.now(clock));
        orderRepository.save(order);

        ticketService.issueTickets(order.getId());
        log.info("Staff test order issued: orderId={}, privateBoat={}, by={}", order.getId(), privateBoat, staffUsername);
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    /**
     * Explicit staff-triggered send for a test order. Uses the resend path once something has
     * already been delivered, so its cooldown and attempt cap still apply.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void sendTestEmail(UUID orderId, String staffUsername) {
        Order order = requireTestOrder(orderId);
        if (order.getEmail() != null && order.getEmail().endsWith(TEST_EMAIL_DOMAIN)) {
            throw new IllegalStateException("У заказа служебный адрес " + TEST_EMAIL_DOMAIN
                    + ", письмо отправлять некуда. Создайте тестовый заказ с реальным адресом.");
        }

        if (order.getTicketsEmailedAt() == null) {
            ticketEmailService.sendTicketsPdf(orderId);
        } else {
            ticketEmailService.resendTicketsPdf(orderId);
        }
        log.info("Staff test email sent: orderId={}, by={}", orderId, staffUsername);
    }

    @Transactional(readOnly = true)
    public Order requireTestOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        if (!Boolean.TRUE.equals(order.getTestPaid())) {
            throw new IllegalStateException("Order " + orderId + " is not a test order");
        }
        return order;
    }

    @Transactional(readOnly = true)
    public List<Order> recentTestOrders() {
        return orderRepository.findAllByTestPaidIsTrueOrderByCreatedAtDesc();
    }

    private void requireNoRealPayment(Order order) {
        if (!paymentRepository.findAllByOrderId(order.getId()).isEmpty()) {
            throw new IllegalStateException("Refusing to test-pay an order that has payment attempts");
        }
    }

    /** Blank means "no real recipient": an unroutable address so nothing can leave by accident. */
    private String normalizeEmail(String requestedEmail) {
        if (requestedEmail == null || requestedEmail.isBlank()) {
            return "staff-test-" + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
        }
        return requestedEmail.trim();
    }
}
