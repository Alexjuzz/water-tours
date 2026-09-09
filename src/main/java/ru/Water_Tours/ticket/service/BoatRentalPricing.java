package ru.Water_Tours.ticket.service;

import java.math.BigDecimal;
import java.util.Map;

public final class BoatRentalPricing {
    private static final Map<Integer, BigDecimal> PRICES = Map.of(
            30, new BigDecimal("3500.00"),
            60, new BigDecimal("6000.00"),
            90, new BigDecimal("9000.00"),
            120, new BigDecimal("11000.00")
    );

    private BoatRentalPricing() {
    }

    public static BigDecimal priceFor(int durationMinutes) {
        BigDecimal price = PRICES.get(durationMinutes);
        if (price == null) {
            throw new IllegalArgumentException("Boat rental duration must be one of: 30, 60, 90, 120 minutes");
        }
        return price;
    }
}
