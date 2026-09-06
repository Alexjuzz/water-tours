package ru.Water_Tours.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trusted allowlist of Telegram chat IDs allowed to redeem tickets via the bot, mirroring the
 * local-checkout.trusted-remote-addresses pattern already used for the test-payment endpoint.
 */
@Component
public class StaffTelegramAuthorization {

    private final Set<Long> trustedChatIds;

    public StaffTelegramAuthorization(@Value("${staff.telegram-chat-ids:}") String chatIds) {
        this.trustedChatIds = Arrays.stream(chatIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isStaff(long chatId) {
        return trustedChatIds.contains(chatId);
    }
}
