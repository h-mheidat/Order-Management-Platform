package com.example.orders.repository;

import com.example.orders.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Outbox rows. The publisher that drains them arrives with the Kafka stage; for now this exists so
 * order creation can write its event inside the same transaction as the order itself.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
