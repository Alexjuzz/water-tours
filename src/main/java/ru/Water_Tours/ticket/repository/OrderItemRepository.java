package ru.Water_Tours.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;

import java.util.UUID;
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}
