package com.watertours.project.repository;

import com.watertours.project.model.entity.order.TicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<TicketOrder, Long> {

    Optional<TicketOrder> findByCartId(String cartId);
}
