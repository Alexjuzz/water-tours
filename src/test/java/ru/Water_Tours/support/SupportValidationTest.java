package ru.Water_Tours.support;

import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.SupportContactKind;

import static org.assertj.core.api.Assertions.assertThat;

class SupportValidationTest {

    @Test
    void controlCharactersAreStrippedAndTextIsTruncated() {
        String sanitized = SupportValidation.sanitizeText("аб\tвг", 100);
        assertThat(sanitized).isEqualTo("аб вг");

        assertThat(SupportValidation.sanitizeText("x".repeat(50), 10)).hasSize(10);
        assertThat(SupportValidation.sanitizeText(null, 10)).isEmpty();
    }

    @Test
    void newlinesSurviveButRunawayBlankLinesCollapse() {
        assertThat(SupportValidation.sanitizeText("одна\nдве\n\n\n\nтри", 100)).isEqualTo("одна\nдве\n\nтри");
    }

    @Test
    void messageLengthBoundsAreEnforced() {
        assertThat(SupportValidation.isAcceptableMessage("коротко")).isFalse();
        assertThat(SupportValidation.isAcceptableMessage("достаточно длинный вопрос")).isTrue();
        assertThat(SupportValidation.isAcceptableMessage("x".repeat(SupportInquiry.MAX_MESSAGE_LENGTH))).isTrue();
        assertThat(SupportValidation.isAcceptableMessage("x".repeat(SupportInquiry.MAX_MESSAGE_LENGTH + 1))).isFalse();
    }

    @Test
    void emailAndPhoneAreRecognised() {
        SupportValidation.Contact email = SupportValidation.parseContact(" Guest@Example.RU ").orElseThrow();
        assertThat(email.kind()).isEqualTo(SupportContactKind.EMAIL);
        assertThat(email.display()).isEqualTo("Guest@Example.RU");
        assertThat(email.dedupeKey()).isEqualTo("guest@example.ru");

        SupportValidation.Contact phone = SupportValidation.parseContact("8 (999) 123-45-67").orElseThrow();
        assertThat(phone.kind()).isEqualTo(SupportContactKind.PHONE);
        assertThat(phone.display()).isEqualTo("8 (999) 123-45-67");
        assertThat(phone.dedupeKey()).isEqualTo("79991234567");
    }

    @Test
    void unanswerableContactsAreRefusedRatherThanStored() {
        assertThat(SupportValidation.parseContact(null)).isEmpty();
        assertThat(SupportValidation.parseContact("   ")).isEmpty();
        assertThat(SupportValidation.parseContact("не скажу")).isEmpty();
        assertThat(SupportValidation.parseContact("999")).isEmpty();
        assertThat(SupportValidation.parseContact("guest@")).isEmpty();
        assertThat(SupportValidation.parseContact("@example.ru")).isEmpty();
    }
}
