package com.watertours.project.repository;


import com.watertours.project.model.entity.ticket.QuickTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuickTicketRepository  extends JpaRepository<QuickTicket, Long> {
}
