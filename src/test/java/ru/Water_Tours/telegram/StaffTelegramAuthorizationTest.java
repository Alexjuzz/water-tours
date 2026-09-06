package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaffTelegramAuthorizationTest {

    @Test
    void emptyConfigTrustsNoOne() {
        StaffTelegramAuthorization auth = new StaffTelegramAuthorization("");

        assertThat(auth.isStaff(123L)).isFalse();
    }

    @Test
    void parsesCommaSeparatedIdsWithWhitespace() {
        StaffTelegramAuthorization auth = new StaffTelegramAuthorization(" 111, 222 ,333");

        assertThat(auth.isStaff(111L)).isTrue();
        assertThat(auth.isStaff(222L)).isTrue();
        assertThat(auth.isStaff(333L)).isTrue();
        assertThat(auth.isStaff(444L)).isFalse();
    }

    @Test
    void ignoresEmptyEntriesFromTrailingCommas() {
        StaffTelegramAuthorization auth = new StaffTelegramAuthorization("111,,222,");

        assertThat(auth.isStaff(111L)).isTrue();
        assertThat(auth.isStaff(222L)).isTrue();
    }
}
