package ru.Water_Tours.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.enums.TicketType;
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
 * provider, and suppresses e-mail by pre-stamping ticketsEmailedAt so
 * {@code findOrderIdsAwaitingTicketEmail} never returns it. It refuses to act on any order it did
 * not create, so a real customer order can never be flipped to paid from here. No refund path, no
 * public endpoint, no local-checkout profile involved.
 */
@Service
public class StaffTestOrderService {

    private static final Logger log = LoggerFactory.getLogger(StaffTestOrderService.class);

    public static final String TEST_EMAIL_DOMAIN = "@test.invalid";
    private static final String TEST_PHONE = "+70000000000";

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TicketService ticketService;
    private final Clock clock;

    public StaffTestOrderService(OrderService orderService, OrderRepository orderRepository,
                                 PaymentRepository paymentRepository, TicketService ticketService, Clock clock) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.ticketService = ticketService;
        this.clock = clock;
    }

    @Transactional
    public Order createIssuedTestOrder(String staffUsername) {
        Order created = orderService.createOrder(
                new OrderRequestDTO(testEmail(), TEST_PHONE, Map.of(TicketType.ADULT, 1)),
                "staff-test-" + UUID.randomUUID());

        Order order = orderRepository.findByIdForUpdate(created.getId()).orElseThrow();
        requireNoRealPayment(order);

        Instant now = Instant.now(clock);
        order.setStatus(OrderStatus.PAID);
        order.setTestPaid(true);
        order.setPaidAt(now);
        // Pre-stamped so the delivery job treats the mail as already handled: a test run must not
        // put a message in front of the SMTP server or a real inbox.
        order.setTicketsEmailedAt(now);
        orderRepository.save(order);

        ticketService.issueTickets(order.getId());
        log.info("Staff test order issued: orderId={}, by={}", order.getId(), staffUsername);
        return orderRepository.findById(order.getId()).orElseThrow();
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
        return orderRepository.findAllByEmailEndingWithIgnoreCaseOrderByCreatedAtDesc(TEST_EMAIL_DOMAIN);
    }

    private void requireNoRealPayment(Order order) {
        if (!paymentRepository.findAllByOrderId(order.getId()).isEmpty()) {
            throw new IllegalStateException("Refusing to test-pay an order that has payment attempts");
        }
    }

    private String testEmail() {
        return "staff-test-" + UUID.randomUUID() + TEST_EMAIL_DOMAIN;
    }
}
