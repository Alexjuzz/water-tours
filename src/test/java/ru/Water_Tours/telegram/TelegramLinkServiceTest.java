package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramLinkServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final TelegramLinkService service = new TelegramLinkService(orderRepository, "water_tours_bot");

    @Test
    void buildDeepLinkEncodesOrderAndTokenAsUrlSafePayload() {
        UUID orderId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        String link = service.buildDeepLink(orderId, token);

        assertThat(link).startsWith("https://t.me/water_tours_bot?start=");
        assertThat(link).matches("https://t\\.me/water_tours_bot\\?start=[A-Za-z0-9_-]+");
        // Telegram silently drops start payloads over 64 chars, delivering a bare /start.
        String payload = link.substring(link.indexOf("start=") + "start=".length());
        assertThat(payload.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void buildDeepLinkStripsLeadingAtFromConfiguredUsername() {
        TelegramLinkService withAt = new TelegramLinkService(orderRepository, "@water_tours_bot");

        String link = withAt.buildDeepLink(UUID.randomUUID(), UUID.randomUUID());

        assertThat(link).startsWith("https://t.me/water_tours_bot?start=");
        assertThat(link).doesNotContain("@");
    }

    @Test
    void buildDeepLinkReturnsNullWhenUsernameNotConfigured() {
        TelegramLinkService noUsername = new TelegramLinkService(orderRepository, "");

        assertThat(noUsername.buildDeepLink(UUID.randomUUID(), UUID.randomUUID())).isNull();
    }

    @Test
    void linkChatPersistsChatIdForValidPayload() {
        UUID orderId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setAccessToken(token);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String payload = service.buildDeepLink(orderId, token).substring("https://t.me/water_tours_bot?start=".length());
        Order linked = service.linkChat(payload, 555L);

        assertThat(linked.getTelegramChatId()).isEqualTo(555L);
    }

    @Test
    void linkChatRejectsMismatchedAccessToken() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setAccessToken(UUID.randomUUID());
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        String payload = encodePayload(orderId, UUID.randomUUID());

        assertThatThrownBy(() -> service.linkChat(payload, 555L)).isInstanceOf(AccessDeniedException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void linkChatRejectsMalformedPayloadWithoutTouchingRepository() {
        assertThatThrownBy(() -> service.linkChat("not-a-valid-payload!!", 555L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void findLinkedOrderDelegatesToRepository() {
        Order order = new Order();
        when(orderRepository.findByTelegramChatId(555L)).thenReturn(Optional.of(order));

        assertThat(service.findLinkedOrder(555L)).contains(order);
    }

    @Test
    void linkChatRejectsWrongSizedPayload() {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        assertThatThrownBy(() -> service.linkChat(payload, 555L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderRepository);
    }

    private static String encodePayload(UUID orderId, UUID accessToken) {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.putLong(orderId.getMostSignificantBits());
        buffer.putLong(orderId.getLeastSignificantBits());
        buffer.putLong(accessToken.getMostSignificantBits());
        buffer.putLong(accessToken.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }
}
