package ru.Water_Tours.ticket.service;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.payment.Payment;
import ru.Water_Tours.ticket.repository.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalCheckoutServiceTest {
    final OrderRepository orders=mock(OrderRepository.class);
    final PaymentRepository payments=mock(PaymentRepository.class);
    final Instant now=Instant.parse("2026-09-06T10:00:00Z");
    final LocalCheckoutService service=new LocalCheckoutService(orders,payments,Clock.fixed(now,ZoneOffset.UTC));
    final UUID id=UUID.randomUUID(), token=UUID.randomUUID();
    Order order(OrderStatus status){Order o=new Order();o.setId(id);o.setAccessToken(token);o.setStatus(status);when(orders.findByIdForUpdate(id)).thenReturn(Optional.of(o));when(orders.save(o)).thenReturn(o);return o;}
    @Test void repeatedConfirmationPreservesOriginalTime(){Order o=order(OrderStatus.DRAFT);service.confirmTestPayment(id,token);assertEquals(now,o.getPaidAt());assertTrue(o.getTestPaid());o.setPaidAt(now.minusSeconds(90));service.confirmTestPayment(id,token);assertEquals(now.minusSeconds(90),o.getPaidAt());verify(orders,times(1)).save(o);}
    @Test void wrongTokenDoesNotMutate(){Order o=order(OrderStatus.DRAFT);assertThrows(AccessDeniedException.class,()->service.confirmTestPayment(id,UUID.randomUUID()));assertEquals(OrderStatus.DRAFT,o.getStatus());verify(orders,never()).save(any());}
    @Test void realPaymentAttemptPreventsSimulation(){Order o=order(OrderStatus.DRAFT);when(payments.findAllByOrderId(id)).thenReturn(List.of(new Payment()));assertThrows(IllegalStateException.class,()->service.confirmTestPayment(id,token));assertNull(o.getPaidAt());verify(orders,never()).save(any());}
    @Test void terminalAndPendingOrdersCannotBeTestPaid(){for(OrderStatus status:List.of(OrderStatus.PAID,OrderStatus.CANCELLED,OrderStatus.EXPIRED,OrderStatus.PENDING_PAYMENT)){Order o=order(status);assertThrows(IllegalStateException.class,()->service.confirmTestPayment(id,token));assertEquals(status,o.getStatus());}verify(orders,never()).save(any());}
}
