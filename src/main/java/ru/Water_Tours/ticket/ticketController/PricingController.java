package ru.Water_Tours.ticket.ticketController;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.service.PricingService;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Public read of the currently published prices (task 7.5) - no secrets, prices are public
 * information. The storefront (WordPress) fetches this instead of holding its own copy, so
 * there is exactly one place prices are edited.
 */
@RestController
public class PricingController {

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/api/v1/prices")
    public Map<String, Object> current() {
        PriceVersion version = pricingService.getCurrent();
        return Map.of(
                "version", version.getVersionNumber(),
                "tickets", Map.of(
                        "ADULT", version.getAdultPrice(),
                        "CHILD", version.getChildPrice(),
                        "BENEFIT", version.getBenefitPrice()
                ),
                "boatRentalByDurationMinutes", Map.<String, BigDecimal>of(
                        "30", version.getBoatPrice30(),
                        "60", version.getBoatPrice60(),
                        "90", version.getBoatPrice90(),
                        "120", version.getBoatPrice120()
                )
        );
    }
}
