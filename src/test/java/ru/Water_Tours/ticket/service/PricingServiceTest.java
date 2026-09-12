package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;
import ru.Water_Tours.ticket.repository.PriceVersionRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class PricingServiceTest {

    // Stands in for the price_versions table: PricingService only ever needs the top version,
    // one version by number, and the full history in descending order - a plain list backs all
    // three without pulling in a real database for this unit test.
    private final List<PriceVersion> rows = new ArrayList<>();
    private final PriceVersionRepository repository = mock(PriceVersionRepository.class);
    private PricingService service;

    @BeforeEach
    void wireFakeRepository() {
        when(repository.findTopByOrderByVersionNumberDesc()).thenAnswer(invocation ->
                rows.stream().max(Comparator.comparingInt(PriceVersion::getVersionNumber)));
        when(repository.findByVersionNumber(anyInt())).thenAnswer(invocation -> {
            int versionNumber = invocation.getArgument(0);
            return rows.stream().filter(v -> v.getVersionNumber() == versionNumber).findFirst();
        });
        when(repository.findAllByOrderByVersionNumberDesc()).thenAnswer(invocation ->
                rows.stream().sorted(Comparator.comparingInt(PriceVersion::getVersionNumber).reversed()).toList());
        when(repository.save(any(PriceVersion.class))).thenAnswer(invocation -> {
            PriceVersion entity = invocation.getArgument(0);
            rows.add(entity);
            return entity;
        });
        service = new PricingService(repository);
    }

    private static PriceValues values(String adult, String child, String benefit,
                                       String boat30, String boat60, String boat90, String boat120) {
        return new PriceValues(new BigDecimal(adult), new BigDecimal(child), new BigDecimal(benefit),
                new BigDecimal(boat30), new BigDecimal(boat60), new BigDecimal(boat90), new BigDecimal(boat120));
    }

    @Test
    void seedsVersion1WhenTableIsEmpty() {
        service.seedIfEmpty();

        PriceVersion current = service.getCurrent();
        assertThat(current.getVersionNumber()).isEqualTo(1);
        assertThat(current.getAdultPrice()).isEqualByComparingTo("1500.00");
        assertThat(current.getBenefitPrice()).isEqualByComparingTo("1020.00");
    }

    @Test
    void doesNotReseedWhenAVersionAlreadyExists() {
        PriceVersion existing = new PriceVersion();
        existing.setVersionNumber(5);
        existing.setAdultPrice(new BigDecimal("2000.00"));
        existing.setChildPrice(new BigDecimal("900.00"));
        existing.setBenefitPrice(new BigDecimal("1100.00"));
        existing.setBoatPrice30(new BigDecimal("3500.00"));
        existing.setBoatPrice60(new BigDecimal("6000.00"));
        existing.setBoatPrice90(new BigDecimal("9000.00"));
        existing.setBoatPrice120(new BigDecimal("11000.00"));
        existing.setPublishedBy("owner");
        rows.add(existing);

        service.seedIfEmpty();

        assertThat(service.getCurrent().getVersionNumber()).isEqualTo(5);
        assertThat(service.getCurrent().getAdultPrice()).isEqualByComparingTo("2000.00");
    }

    @Test
    void publishValidatesNegativeAmounts() {
        service.seedIfEmpty();

        assertThatThrownBy(() -> service.publish(
                        values("-1", "800", "1020", "3500", "6000", "9000", "11000"), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adultPrice");
    }

    @Test
    void publishValidatesMoreThanTwoDecimalPlaces() {
        service.seedIfEmpty();

        assertThatThrownBy(() -> service.publish(
                        values("1500.123", "800", "1020", "3500", "6000", "9000", "11000"), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adultPrice");
    }

    @Test
    void publishRejectsNullAmount() {
        service.seedIfEmpty();

        assertThatThrownBy(() -> service.publish(
                        new PriceValues(null, new BigDecimal("800"), new BigDecimal("1020"),
                                new BigDecimal("3500"), new BigDecimal("6000"), new BigDecimal("9000"), new BigDecimal("11000")),
                        "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishCreatesANewVersionAndDoesNotMutateHistory() {
        service.seedIfEmpty();
        int before = service.getCurrent().getVersionNumber();

        PriceVersion published = service.publish(
                values("1600", "850", "1100", "3600", "6100", "9100", "11100"), "owner");

        assertThat(published.getVersionNumber()).isEqualTo(before + 1);
        assertThat(service.getCurrent().getVersionNumber()).isEqualTo(published.getVersionNumber());
        assertThat(service.getTicketPrice(TicketType.ADULT)).isEqualByComparingTo("1600");
        assertThat(service.getHistory()).hasSize(2);
        assertThat(service.getHistory().get(1).getAdultPrice()).isEqualByComparingTo("1500.00");
    }

    @Test
    void rollbackRepublishesOldValuesAsANewVersion() {
        service.seedIfEmpty();
        service.publish(values("1600", "850", "1100", "3600", "6100", "9100", "11100"), "owner");

        PriceVersion rolledBack = service.rollbackTo(1, "owner");

        assertThat(rolledBack.getVersionNumber()).isEqualTo(3);
        assertThat(rolledBack.getAdultPrice()).isEqualByComparingTo("1500.00");
        assertThat(service.getHistory()).hasSize(3);
    }

    @Test
    void rollbackToUnknownVersionThrows() {
        service.seedIfEmpty();

        assertThatThrownBy(() -> service.rollbackTo(999, "owner"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getBoatPriceRejectsUnsupportedDuration() {
        service.seedIfEmpty();

        assertThatThrownBy(() -> service.getBoatPrice(45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30, 60, 90, 120");
    }
}
