package ru.Water_Tours.ticket.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phone numbers reach us exactly as a customer typed them into the public form - "+7 (999)
 * 123-45-67", "8 999 1234567", "9991234567" - and existing rows are deliberately left that way:
 * rewriting stored customer records to fit a lookup would change data that nobody asked us to
 * change. So normalisation happens on read instead.
 *
 * Canonical form is digits only with the Russian trunk prefix folded to the country code, and a
 * lookup asks for every spelling that means the same number. Anything shorter than a full number
 * is refused, so a support search can never turn into an enumeration of customers by prefix.
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1);
        }
        if (digits.length() == 10) {
            // A bare Russian number without any country or trunk prefix.
            digits = "7" + digits;
        }
        if (digits.length() < 11 || digits.length() > 15) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    /**
     * The digit strings a stored, hand-typed number may reduce to for the same subscriber. Only
     * the Russian numbering plan gets the 8/no-prefix variants; anything else matches exactly.
     */
    public static List<String> storedVariants(String normalized) {
        List<String> variants = new ArrayList<>(3);
        variants.add(normalized);
        if (normalized.length() == 11 && normalized.startsWith("7")) {
            variants.add("8" + normalized.substring(1));
            variants.add(normalized.substring(1));
        }
        return variants;
    }
}
