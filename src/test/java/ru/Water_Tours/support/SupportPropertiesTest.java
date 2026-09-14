package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupportPropertiesTest {

    @Test
    void supportIsOffUntilAnOwnerPrivateChatIdIsConfigured() {
        assertThat(new SupportProperties(true, "").isEnabled()).isFalse();
        assertThat(new SupportProperties(true, "   ").isEnabled()).isFalse();
        assertThat(new SupportProperties(true, "not-a-number").isEnabled()).isFalse();
        assertThat(new SupportProperties(true, "0").isEnabled()).isFalse();
        assertThat(new SupportProperties(true, " 777 ").isEnabled()).isTrue();
    }

    @Test
    void aGroupOrChannelIdIsRefusedAsTheSupportInbox() {
        // Telegram groups, supergroups and channels all have negative ids. Accepting one would
        // publish every customer's contact and question to whoever is in that chat.
        SupportProperties group = new SupportProperties(true, "-1001234567890");

        assertThat(group.isEnabled()).isFalse();
        assertThat(group.isOwner(-1001234567890L)).isFalse();
    }

    @Test
    void theKillSwitchTurnsSupportOffWithoutLosingTheConfiguredId() {
        assertThat(new SupportProperties(false, "777").isEnabled()).isFalse();
    }

    @Test
    void onlyTheConfiguredChatIsTheOwner() {
        SupportProperties properties = new SupportProperties(true, "777");

        assertThat(properties.isOwner(777L)).isTrue();
        assertThat(properties.isOwner(778L)).isFalse();
        assertThat(properties.isOwner(0L)).isFalse();
        // Staff chat ids are a different allowlist and grant nothing here.
        assertThat(properties.isOwner(999L)).isFalse();
    }

    @Test
    void anUnconfiguredInstanceHasNoOwnerAtAll() {
        SupportProperties properties = new SupportProperties(true, "");

        assertThat(properties.isOwner(0L)).isFalse();
        assertThat(properties.getOwnerChatId()).isZero();
    }
}
