package com.example.orders.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Record that a Kafka event has already been handled - the idempotency ledger for stage 12.
 *
 * <p>Kafka gives at-least-once delivery, so the same {@code ORDER_CREATED 100} can arrive twice
 * (rebalance, retry, redelivery after a failed commit). Handling it twice would send two
 * notifications or write two audit rows.
 *
 * <p>The event id is the primary key, which is what makes this safe under concurrency. The consumer
 * inserts this row <em>in the same transaction</em> as its side effect: either both commit, or the
 * insert violates the primary key and the duplicate is skipped. A "select then insert if absent"
 * check would leave a window in which two consumers both see nothing and both proceed.
 */
@Entity
@Table(name = "processed_events")
@Getter
public class ProcessedEvent {

    /** Assigned by the producer (the outbox row's {@code event_id}), never generated here. */
    @Id
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {
        // Required by JPA.
    }

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }

    /**
     * Safe to base on the id here, unlike the generated-id entities: {@code eventId} is a natural
     * key supplied before persisting, so it never changes underneath a hash-based collection.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEvent event)) {
            return false;
        }
        return eventId != null && eventId.equals(event.getEventId());
    }

    @Override
    public int hashCode() {
        return eventId == null ? 0 : eventId.hashCode();
    }

    @Override
    public String toString() {
        return "ProcessedEvent{eventId='" + eventId + "', eventType='" + eventType + "'}";
    }
}
