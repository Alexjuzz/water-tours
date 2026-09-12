package ru.Water_Tours.ticket.model.pricing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One published set of prices. Rows are append-only: publishing (including a rollback) always
 * inserts a new row with the next version number, so history/audit is never rewritten and an
 * order's stored priceVersion always resolves to the exact values it was charged.
 */
@Entity
@Getter
@Setter
@Table(name = "price_versions")
public class PriceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_number", nullable = false, unique = true)
    private int versionNumber;

    @Column(name = "adult_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal adultPrice;

    @Column(name = "child_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal childPrice;

    @Column(name = "benefit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal benefitPrice;

    @Column(name = "boat_price_30", nullable = false, precision = 12, scale = 2)
    private BigDecimal boatPrice30;

    @Column(name = "boat_price_60", nullable = false, precision = 12, scale = 2)
    private BigDecimal boatPrice60;

    @Column(name = "boat_price_90", nullable = false, precision = 12, scale = 2)
    private BigDecimal boatPrice90;

    @Column(name = "boat_price_120", nullable = false, precision = 12, scale = 2)
    private BigDecimal boatPrice120;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "published_by", nullable = false)
    private String publishedBy;

    @PrePersist
    void prePersist() {
        if (publishedAt == null) publishedAt = Instant.now();
    }
}
