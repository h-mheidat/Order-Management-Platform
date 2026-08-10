package com.example.orders.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An event awaiting publication to Kafka - the outbox of stage 19.
 *
 * <p>The problem this solves: "save the order, then send to Kafka" has no atomicity. If the send
 * fails the order exists with no event; if the commit fails after a successful send, consumers act
 * on an order that does not exist. Two systems, no shared transaction.
 *
 * <p>Instead, the order and this row commit together in one local transaction. A separate publisher
 * polls PENDING rows and sends them. Kafka being down then delays events; it never loses them and
 * never blocks order creation.
 */
@Entity
@Table(name = "outbox_events")
@Getter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_events_seq")
    @SequenceGenerator(name = "outbox_events_seq", sequenceName = "outbox_events_seq",
            allocationSize = 50)
    private Long id;

    /**
     * The event's identity as consumers see it. Generated once, when the row is created - not at
     * publish time, so a retrying publisher cannot mint a second identity for the same event. This
     * is the value the consumer stores in {@link ProcessedEvent}.
     */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * The serialized event, stored as {@code jsonb}.
     *
     * <p>Kept as a String rather than a mapped object graph on purpose: the payload is a message
     * contract, frozen at the moment it was written. If it were mapped to a class, refactoring that
     * class would retroactively change the meaning of events already sitting in the outbox.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    /** Retry counter, used to stop retrying forever and to alert on rows that keep failing. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected OutboxEvent() {
        // Required by JPA.
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.eventId = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    /**
     * Marks the row published.
     *
     * <p>Sets {@code publishedAt} in the same call because {@code ck_outbox_events_published_at}
     * refuses a PUBLISHED row without a timestamp - the state and its evidence cannot drift apart.
     */
    public void markPublished(OffsetDateTime publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    /** Records a failed attempt. Stays PENDING so the publisher will pick it up again. */
    public void recordFailedAttempt() {
        this.attempts++;
    }

    /** Gives up on this row, leaving it in the table for alerting and manual replay. */
    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEvent event)) {
            return false;
        }
        return id != null && id.equals(event.getId());
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }

    @Override
    public String toString() {
        // No payload: it can be large, and it may carry customer data into the logs.
        return "OutboxEvent{id=" + id + ", eventId=" + eventId + ", eventType='" + eventType
                + "', status=" + status + ", attempts=" + attempts + '}';
    }
}
