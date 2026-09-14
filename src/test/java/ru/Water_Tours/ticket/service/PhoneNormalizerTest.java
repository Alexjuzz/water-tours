package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The support search must find the order a caller is holding the phone for, whatever shape that
 * number was typed in at checkout - and must find nothing at all on a partial value.
 */
class PhoneNormalizerTest {

    @Test
    void everySpellingOfOneRussianNumberNormalisesToTheSameDigits() {
        assertThat(PhoneNormalizer.normalize("+7 (999) 123-45-67")).contains("79991234567");
        assertThat(PhoneNormalizer.normalize("8 999 123 45 67")).contains("79991234567");
        assertThat(PhoneNormalizer.normalize("89991234567")).contains("79991234567");
        assertThat(PhoneNormalizer.normalize("9991234567")).contains("79991234567");
        assertThat(PhoneNormalizer.normalize("  +7-999-1234567  ")).contains("79991234567");
    }

    @Test
    void aPartialNumberIsRefusedSoTheSearchCannotEnumerate() {
        assertThat(PhoneNormalizer.normalize("999123")).isEmpty();
        assertThat(PhoneNormalizer.normalize("+7 999")).isEmpty();
        assertThat(PhoneNormalizer.normalize("")).isEmpty();
        assertThat(PhoneNormalizer.normalize(null)).isEmpty();
        assertThat(PhoneNormalizer.normalize("не телефон")).isEmpty();
    }

    @Test
    void lookupAsksForEveryShapeAStoredNumberMayHaveBeenWrittenIn() {
        assertThat(PhoneNormalizer.storedVariants("79991234567"))
                .containsExactlyInAnyOrder("79991234567", "89991234567", "9991234567");
    }

    @Test
    void aNonRussianNumberIsMatchedExactlyAndNothingElse() {
        String normalized = PhoneNormalizer.normalize("+49 30 123456789").orElseThrow();
        assertThat(normalized).isEqualTo("4930123456789");
        assertThat(PhoneNormalizer.storedVariants(normalized)).containsExactly("4930123456789");
    }
}
