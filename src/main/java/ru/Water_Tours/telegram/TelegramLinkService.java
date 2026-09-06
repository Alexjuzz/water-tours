package ru.Water_Tours.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class TelegramLinkService {

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
     * Telegram deep-link start payloads only allow [A-Za-z0-9_-], which is exactly the
     * base64url alphabet, so orderId:accessToken round-trips without extra escaping.
     */
    public String buildDeepLink(UUID orderId, UUID accessToken) {
        if (botUsername == null || botUsername.isBlank()) {
            return null;
        }
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((orderId + ":" + accessToken).getBytes(StandardCharsets.UTF_8));
        return "https://t.me/" + botUsername + "?start=" + payload;
    }

    @Transactional
    public Order linkChat(String payload, long chatId) {
        String[] parts = decode(payload);
        UUID orderId = UUID.fromString(parts[0]);
        UUID accessToken = UUID.fromString(parts[1]);

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

    private String[] decode(String payload) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IllegalArgumentException("Malformed link payload");
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed link payload", e);
        }
    }
}
