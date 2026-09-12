package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import ru.Water_Tours.enums.BoatRouteType;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.BoatRentalRequestDTO;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.order.OrderRequestDTO;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PricingService pricingService;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void stubCurrentPrices() {
        // Same figures the old hardcoded TicketProperties/BoatRentalPricing used, kept here so
        // this test's expected totals don't change: CHILD 1000*(1-0.2)=800, ADULT 1500, BENEFIT
        // 1200*(1-0.15)=1020, boat 30/60/90/120 = 3500/6000/9000/11000.
        PriceVersion version = new PriceVersion();
        version.setVersionNumber(1);
        version.setAdultPrice(new BigDecimal("1500.00"));
        version.setChildPrice(new BigDecimal("800.00"));
        version.setBenefitPrice(new BigDecimal("1020.00"));
        version.setBoatPrice30(new BigDecimal("3500.00"));
        version.setBoatPrice60(new BigDecimal("6000.00"));
        version.setBoatPrice90(new BigDecimal("9000.00"));
        version.setBoatPrice120(new BigDecimal("11000.00"));
        lenient().when(pricingService.getCurrent()).thenReturn(version);
    }

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
        assertThat(result.getPriceVersion()).isEqualTo(1);
    }

    @Test
    void createOrder_afterAPriceChange_usesTheNewPriceButDoesNotTouchThePreviousOrder() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", "+10000000000", Map.of(TicketType.ADULT, 1));

        Order firstOrder = orderService.createOrder(dto, null);
        assertThat(firstOrder.getTotalAmount()).isEqualByComparingTo("1500.00");
        assertThat(firstOrder.getPriceVersion()).isEqualTo(1);

        PriceVersion republished = new PriceVersion();
        republished.setVersionNumber(2);
        republished.setAdultPrice(new BigDecimal("1700.00"));
        republished.setChildPrice(new BigDecimal("800.00"));
        republished.setBenefitPrice(new BigDecimal("1020.00"));
        republished.setBoatPrice30(new BigDecimal("3500.00"));
        republished.setBoatPrice60(new BigDecimal("6000.00"));
        republished.setBoatPrice90(new BigDecimal("9000.00"));
        republished.setBoatPrice120(new BigDecimal("11000.00"));
        when(pricingService.getCurrent()).thenReturn(republished);

        Order secondOrder = orderService.createOrder(dto, null);

        assertThat(secondOrder.getTotalAmount()).isEqualByComparingTo("1700.00");
        assertThat(secondOrder.getPriceVersion()).isEqualTo(2);
        // The first order's own stored amount is untouched by the later price change.
        assertThat(firstOrder.getTotalAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void createPrivateBoatOrder_usesOnlyFixedDurationPrices() {
        Map<Integer, BigDecimal> prices = Map.of(
                30, new BigDecimal("3500.00"),
                60, new BigDecimal("6000.00"),
                90, new BigDecimal("9000.00"),
                120, new BigDecimal("11000.00"));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        prices.forEach((minutes, expected) -> {
            OrderRequestDTO dto = new OrderRequestDTO("user@example.com", "+10000000000", null,
                    new BoatRentalRequestDTO(minutes, 6, BoatRouteType.CUSTOM, "Мой маршрут"));

            Order result = orderService.createOrder(dto, null);

            assertThat(result.getOrderType()).isEqualTo(OrderType.PRIVATE_BOAT);
            assertThat(result.getTotalAmount()).isEqualByComparingTo(expected);
            assertThat(result.getOrderItems()).singleElement().satisfies(item -> {
                assertThat(item.getType()).isEqualTo(TicketType.PRIVATE_BOAT);
                assertThat(item.getQuantity()).isEqualTo(1);
            });
        });
    }

    @Test
    void createPrivateBoatOrder_rejectsUnsupportedDuration() {
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", null, null,
                new BoatRentalRequestDTO(45, 2, BoatRouteType.ASSISTED, null));

        assertThatThrownBy(() -> orderService.createOrder(dto, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30, 60, 90, 120");
    }

    @Test
    void createPrivateBoatOrder_acceptsOnlyOneToSixGuests() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        for (int guests = 1; guests <= 6; guests++) {
            Order result = orderService.createOrder(new OrderRequestDTO("user@example.com", null, null,
                    new BoatRentalRequestDTO(30, guests, BoatRouteType.ASSISTED, null)), null);
            assertThat(result.getBoatGuestCount()).isEqualTo(guests);
        }
        for (int guests : new int[]{0, 7}) {
            OrderRequestDTO invalid = new OrderRequestDTO("user@example.com", null, null,
                    new BoatRentalRequestDTO(30, guests, BoatRouteType.ASSISTED, null));
            assertThatThrownBy(() -> orderService.createOrder(invalid, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 and 6");
        }
    }

    @Test
    void createOrder_rejectsMixingPassengerTicketsWithPrivateBoat() {
        OrderRequestDTO dto = new OrderRequestDTO("user@example.com", null, Map.of(TicketType.ADULT, 1),
                new BoatRentalRequestDTO(60, 4, BoatRouteType.CUSTOM, null));

        assertThatThrownBy(() -> orderService.createOrder(dto, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be combined");
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
