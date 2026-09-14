package ru.Water_Tours.support;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.Water_Tours.enums.SupportStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportInquiryRepository extends JpaRepository<SupportInquiry, UUID> {

    Optional<SupportInquiry> findByReference(String reference);

    /**
     * A repeat submission of the same text to the same contact inside the dedupe window returns
     * the inquiry that already exists instead of creating a second one, so a double click or an
     * impatient retry cannot flood the owner.
     */
    Optional<SupportInquiry> findFirstByDedupeHashAndCreatedAtAfterOrderByCreatedAtDesc(String dedupeHash, Instant after);

    @Query("select i from SupportInquiry i where i.status = :status and (i.nextNotifyAt is null or i.nextNotifyAt <= :now) "
            + "order by i.createdAt asc")
    List<SupportInquiry> findDueForNotification(@Param("status") SupportStatus status, @Param("now") Instant now,
                                                Pageable pageable);

    List<SupportInquiry> findTop50ByOrderByCreatedAtDesc();
}
