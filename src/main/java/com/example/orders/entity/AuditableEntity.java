package com.example.orders.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Creation and modification timestamps for entities that need them.
 *
 * <p>{@code OffsetDateTime} maps to {@code timestamptz}, so the instant is stored with its offset
 * rather than as a wall-clock reading whose meaning depends on the server's timezone. Using
 * {@code LocalDateTime} here is the classic way to make timestamps wrong the first time the
 * application moves region or the clocks change.
 *
 * <p>Hibernate populates both columns, and the columns also carry a database default. That is
 * intentional redundancy: the default protects rows written by a migration or by hand.
 *
 * <p>No setters - these are not values the application layer is allowed to choose.
 */
@Getter
@MappedSuperclass
public abstract class AuditableEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
