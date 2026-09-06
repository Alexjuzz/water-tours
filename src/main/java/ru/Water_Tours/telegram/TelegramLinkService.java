package ru.Water_Tours.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class TelegramLinkService {

    private static final int PAYLOAD_BYTES = 32; // two UUIDs, 16 bytes each

    private final OrderRepository orderRepository;
    private final String botUsername;

    public TelegramLinkService(OrderRepository orderRepository,
                                @Value("${telegram.bot-username:}") String botUsername) {
        this.orderRepository = orderRepository;
        // Telegram usernames are conventionally written with a leading "@"; t.me links need it stripped.
        this.botUsername = botUsername != null && botUsername.startsWith("@")
                ? botUsername.substring(1)
                : botUsername;
    }

    /**
     * Telegram truncates/drops a deep-link start parameter over 64 characters. Two UUIDs as
     * text (36 chars each) plus a separator, base64url-encoded, comes to ~98 chars - over the
     * limit. Packing each UUID's raw 16 bytes instead keeps the encoded payload at ~43 chars.
     */
    public String buildDeepLink(UUID orderId, UUID accessToken) {
        if (botUsername == null || botUsername.isBlank()) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_BYTES);
        buffer.putLong(orderId.getMostSignificantBits());
        buffer.putLong(orderId.getLeastSignificantBits());
        buffer.putLong(accessToken.getMostSignificantBits());
        buffer.putLong(accessToken.getLeastSignificantBits());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        return "https://t.me/" + botUsername + "?start=" + payload;
    }

    @Transactional
    public Order linkChat(String payload, long chatId) {
        UUID[] parts = decode(payload);
        UUID orderId = parts[0];
        UUID accessToken = parts[1];

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        if (order.getAccessToken() == null || !order.getAccessToken().equals(accessToken)) {
            throw new AccessDeniedException("Invalid access token");
        }
        order.setTelegramChatId(chatId);
        return orderRepository.save(order);
    }

    public Optional<Order> findLinkedOrder(long chatId) {
        return orderRepository.findByTelegramChatId(chatId);
    }

    private UUID[] decode(String payload) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed link payload", e);
        }
        if (bytes.length != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Malformed link payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        UUID orderId = new UUID(buffer.getLong(), buffer.getLong());
        UUID accessToken = new UUID(buffer.getLong(), buffer.getLong());
        return new UUID[]{orderId, accessToken};
    }
}
