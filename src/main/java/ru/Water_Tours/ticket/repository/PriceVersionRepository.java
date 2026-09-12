package ru.Water_Tours.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.Water_Tours.ticket.model.pricing.PriceVersion;

import java.util.List;
import java.util.Optional;

public interface PriceVersionRepository extends JpaRepository<PriceVersion, java.util.UUID> {
    Optional<PriceVersion> findTopByOrderByVersionNumberDesc();

    Optional<PriceVersion> findByVersionNumber(int versionNumber);

    List<PriceVersion> findAllByOrderByVersionNumberDesc();
}
