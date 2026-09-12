package ru.Water_Tours.ticket.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.repository.PriceVersionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single authoritative source for sellable prices (task 7). Order creation reads {@link #getCurrent()}
 * and snapshots the version number into the order, so a later publish never changes an existing
 * order's charge. Publishing (including rollback) always appends a new version - history is never
 * rewritten.
 */
@Service
public class PricingService {

    // Seed values match what was previously hardcoded in TicketProperties/BoatRentalPricing.
    // Note: the old TicketProperties computed BENEFIT as 1200 * (1 - 0.15) = 1020, but the
    // WordPress widget displayed 1000 - the two had already drifted apart. The seed uses 1020,
    // the actual amount customers were being charged; see ops/security/INVENTORY.md.
    private static final PriceValues SEED_VALUES = new PriceValues(
            new BigDecimal("1500.00"), new BigDecimal("800.00"), new BigDecimal("1020.00"),
            new BigDecimal("3500.00"), new BigDecimal("6000.00"), new BigDecimal("9000.00"), new BigDecimal("11000.00")
    );

    private final PriceVersionRepository repository;
    private final AtomicReference<PriceVersion> current = new AtomicReference<>();

    public PricingService(PriceVersionRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    void seedIfEmpty() {
        PriceVersion latest = repository.findTopByOrderByVersionNumberDesc().orElse(null);
        if (latest == null) {
            latest = publishInternal(SEED_VALUES, "system-seed");
        }
        current.set(latest);
    }

    public PriceVersion getCurrent() {
        PriceVersion snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("No published price version available");
        }
        return snapshot;
    }

    public BigDecimal getTicketPrice(TicketType type) {
        PriceVersion version = getCurrent();
        return switch (type) {
            case ADULT -> version.getAdultPrice();
            case CHILD -> version.getChildPrice();
            case BENEFIT -> version.getBenefitPrice();
            case PRIVATE_BOAT -> throw new IllegalArgumentException("Private boat price depends on rental duration");
        };
    }

    public BigDecimal getBoatPrice(int durationMinutes) {
        PriceVersion version = getCurrent();
        return switch (durationMinutes) {
            case 30 -> version.getBoatPrice30();
            case 60 -> version.getBoatPrice60();
            case 90 -> version.getBoatPrice90();
            case 120 -> version.getBoatPrice120();
            default -> throw new IllegalArgumentException("Boat rental duration must be one of: 30, 60, 90, 120 minutes");
        };
    }

    public List<PriceVersion> getHistory() {
        return repository.findAllByOrderByVersionNumberDesc();
    }

    @Transactional
    public synchronized PriceVersion publish(PriceValues values, String publishedBy) {
        validate(values);
        PriceVersion saved = publishInternal(values, publishedBy);
        current.set(saved);
        return saved;
    }

    @Transactional
    public synchronized PriceVersion rollbackTo(int versionNumber, String publishedBy) {
        PriceVersion target = repository.findByVersionNumber(versionNumber)
                .orElseThrow(() -> new NoSuchElementException("No such price version: " + versionNumber));
        PriceValues values = new PriceValues(target.getAdultPrice(), target.getChildPrice(), target.getBenefitPrice(),
                target.getBoatPrice30(), target.getBoatPrice60(), target.getBoatPrice90(), target.getBoatPrice120());
        return publish(values, publishedBy);
    }

    private PriceVersion publishInternal(PriceValues values, String publishedBy) {
        int nextVersion = repository.findTopByOrderByVersionNumberDesc()
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
        PriceVersion version = new PriceVersion();
        version.setVersionNumber(nextVersion);
        version.setAdultPrice(values.adultPrice());
        version.setChildPrice(values.childPrice());
        version.setBenefitPrice(values.benefitPrice());
        version.setBoatPrice30(values.boatPrice30());
        version.setBoatPrice60(values.boatPrice60());
        version.setBoatPrice90(values.boatPrice90());
        version.setBoatPrice120(values.boatPrice120());
        version.setPublishedBy(publishedBy);
        return repository.save(version);
    }

    private void validate(PriceValues values) {
        requireValidRubAmount(values.adultPrice(), "adultPrice");
        requireValidRubAmount(values.childPrice(), "childPrice");
        requireValidRubAmount(values.benefitPrice(), "benefitPrice");
        requireValidRubAmount(values.boatPrice30(), "boatPrice30");
        requireValidRubAmount(values.boatPrice60(), "boatPrice60");
        requireValidRubAmount(values.boatPrice90(), "boatPrice90");
        requireValidRubAmount(values.boatPrice120(), "boatPrice120");
    }

    private void requireValidRubAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException(field + " must not have more than 2 decimal places");
        }
    }
}
