package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void createOrder_withMixedTickets_calculatesActualTotal() {
        // CHILD: 1000 * (1 - 0.2) = 800, ADULT: 1500 * (1 - 0.0) = 1500, BENEFIT: 1200 * (1 - 0.15) = 1020
        // total = 2*800 + 1*1500 + 3*1020 = 6160
        Map<TicketType, Integer> tickets = new EnumMap<>(TicketType.class);
        tickets.put(TicketType.CHILD, 2);
        tickets.put(TicketType.ADULT, 1);
        tickets.put(TicketType.BENEFIT, 3);
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", "+10000000000", tickets);

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(dto, null);

        assertThat(result.getOrderItems()).hasSize(3);
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("6160.00"));
    }

    @Test
    void createOrder_withEmptyTickets_throwsIllegalArgumentException() {
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", "+10000000000", Map.of());

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto, null));
    }

    @Test
    void createOrder_withAllZeroQuantities_throwsIllegalArgumentException() {
        Map<TicketType, Integer> tickets = new EnumMap<>(TicketType.class);
        tickets.put(TicketType.CHILD, 0);
        tickets.put(TicketType.ADULT, 0);
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", "+10000000000", tickets);

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto, null));
    }

    @Test
    void checkAccess_withCorrectToken_doesNotThrow() {
        UUID orderId = UUID.randomUUID();
        UUID accessToken = UUID.randomUUID();
        Order order = new Order();
        order.setAccessToken(accessToken);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderService.checkAccess(orderId, accessToken);
    }

    @Test
    void checkAccess_withWrongToken_throwsAccessDeniedException() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setAccessToken(UUID.randomUUID());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class, () -> orderService.checkAccess(orderId, UUID.randomUUID()));
    }

    @Test
    void checkAccess_withOrderNotFound_throwsNoSuchElementException() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> orderService.checkAccess(orderId, UUID.randomUUID()));
    }
}
