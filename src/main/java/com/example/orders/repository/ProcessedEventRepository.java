package com.example.orders.repository;

import com.example.orders.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** The idempotency ledger. Its primary key is the guarantee - see {@link ProcessedEvent}. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
