package com.example.orders.kafka;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.orders.entity.OutboxEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What actually travels on the topic: an identified, self-describing wrapper around a payload.
 *
 * <p>The envelope exists so consumers never have to infer anything from the message. {@code eventId}
 * gives them an idempotency key, {@code eventType} lets them route without guessing from the payload
 * shape, and {@code occurredAt} is when the thing happened - not when it was published, which may be
 * much later if the broker was unavailable. Consumers that compute business time from a Kafka timestamp
 * get it wrong exactly when an outage makes it matter.
 *
 * <p>{@code payload} stays a {@link JsonNode} rather than a typed class. A consumer of ORDER_CREATED
 * should not fail to deserialize the envelope because an unrelated event type gained a field, and the
 * envelope should not need a Java class per event to be readable at all.
 *
 * @param eventId       stable identity, minted once by the outbox row and preserved across republishes
 * @param eventType     discriminator, e.g. {@code ORDER_CREATED}
 * @param aggregateType which kind of thing changed
 * @param aggregateId   which one
 * @param occurredAt    when it happened
 * @param payload       the event body
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        OffsetDateTime occurredAt,
        JsonNode payload) {

    public static EventEnvelope from(OutboxEvent row, JsonNode payload) {
        return new EventEnvelope(row.getEventId(), row.getEventType(), row.getAggregateType(),
                row.getAggregateId(), row.getCreatedAt(), payload);
    }
}
