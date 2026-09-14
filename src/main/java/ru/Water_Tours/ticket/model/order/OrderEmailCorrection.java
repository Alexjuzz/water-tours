package ru.Water_Tours.ticket.model.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.Water_Tours.enums.EmailCorrectionOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of a staff correction of the ticket delivery address.
 *
 * Kept as its own row rather than as columns on the order so both the wrong and the corrected
 * address survive, together with who changed them and why, even after further corrections or
 * resends. The order id is a plain column, not a mapped association: this record must outlive
 * anything that happens to the order aggregate.
 *
 * Addresses live here because an audit trail without them cannot answer "where did it go"; they
 * are never written to the application log.
 */
@Entity
@Getter
@Setter
@Table(name = "order_email_corrections",
        indexes = @Index(name = "idx_order_email_corrections_order_id", columnList = "order_id, created_at"))
public class OrderEmailCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "old_email")
    private String oldEmail;

    @Column(name = "new_email", nullable = false)
    private String newEmail;

    @Column(name = "staff_principal", nullable = false, length = 120)
    private String staffPrincipal;

    @Column(name = "reason", nullable = false, length = 300)
    private String reason;

    /** When the correction was applied to the order - written before the send is attempted. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When the send outcome became known. Null while the attempt is still in flight. */
    @Column(name = "settled_at")
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private EmailCorrectionOutcome outcome;

    /** Exception type of a failed send. Never a message, so no address can leak in here. */
    @Column(name = "failure_type", length = 120)
    private String failureType;
}
