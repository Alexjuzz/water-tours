package ru.Water_Tours.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Support is OFF unless an explicit owner recipient is configured.
 *
 * The recipient is deliberately its own setting and NOT derived from
 * {@code staff.telegram-chat-ids}: that allowlist says who may redeem a ticket, which is a
 * different decision from who receives customers' questions and personal contacts. Taking the
 * first staff id, or fanning out to every staff member, would spread customer data by accident.
 *
 * It must be a positive id. Telegram gives groups, supergroups and channels negative ids, so a
 * negative value here is refused rather than quietly turning a group into the support inbox.
 */
@Component
public class SupportProperties {

    private static final Logger log = LoggerFactory.getLogger(SupportProperties.class);

    private final boolean featureEnabled;
    private final long ownerChatId;

    public SupportProperties(@Value("${support.enabled:true}") boolean featureEnabled,
                             @Value("${support.owner-telegram-chat-id:}") String ownerChatId) {
        this.featureEnabled = featureEnabled;
        this.ownerChatId = parseOwnerChatId(ownerChatId);
    }

    private long parseOwnerChatId(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("support.owner-telegram-chat-id is not a number; customer support stays disabled");
            return 0L;
        }
        if (parsed <= 0) {
            log.warn("support.owner-telegram-chat-id must be a positive private chat id "
                    + "(a negative id is a group or channel); customer support stays disabled");
            return 0L;
        }
        return parsed;
    }

    /** True only when the feature flag is on AND a usable owner private chat id is configured. */
    public boolean isEnabled() {
        return featureEnabled && ownerChatId > 0;
    }

    /** Valid only when {@link #isEnabled()}. */
    public long getOwnerChatId() {
        return ownerChatId;
    }

    /** The one chat allowed to use /reply. Never a username and never a forwarded identity. */
    public boolean isOwner(long chatId) {
        return ownerChatId > 0 && ownerChatId == chatId;
    }
}
