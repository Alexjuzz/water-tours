package ru.Water_Tours.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.Water_Tours.ticket.model.ticket.Ticket;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    boolean existsByOrderId(UUID orderId);

    List<Ticket> findAllByOrderId(UUID orderId);

    Optional<Ticket> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.code = :code")
    Optional<Ticket> findByCodeForUpdate(@Param("code") String code);

}
