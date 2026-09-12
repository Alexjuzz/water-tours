package ru.Water_Tours.ticket.service;

import java.math.BigDecimal;

/**
 * Editable price fields (task 7.1 inventory), independent of persistence/version bookkeeping.
 */
public record PriceValues(
        BigDecimal adultPrice,
        BigDecimal childPrice,
        BigDecimal benefitPrice,
        BigDecimal boatPrice30,
        BigDecimal boatPrice60,
        BigDecimal boatPrice90,
        BigDecimal boatPrice120
) {
}
