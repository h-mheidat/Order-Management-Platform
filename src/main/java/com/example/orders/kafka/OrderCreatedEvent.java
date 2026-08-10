package com.example.orders.kafka;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.example.orders.entity.Order;

/**
 * Published when an order is created.
 *
 * <p>A published event is a contract with every consumer, and unlike an API response it cannot be
 * versioned by asking callers to migrate - messages already in the topic keep their old shape forever.
 * So it carries scalars, deliberately: no entity, no nested item list, nothing whose Java class could
 * be refactored into a different serialized form.
 *
 * <p>{@code eventId} is not set here. It comes from the outbox row, which mints it once at creation, so
 * a republished event keeps the identity consumers already recorded in {@code processed_events}.
 *
 * @param eventType  the discriminator consumers switch on
 * @param orderId    which order
 * @param customerId who placed it
 * @param totalPrice what it came to
 * @param occurredAt when it happened - the event's own time, not the time it was published, which
 *                   may be much later if the broker was down
 */
public record OrderCreatedEvent(
        String eventType,
        Long orderId,
        Long customerId,
        BigDecimal totalPrice,
        int itemCount,
        OffsetDateTime occurredAt) {

    public static final String TYPE = "ORDER_CREATED";

    public static OrderCreatedEvent of(Order order) {
        return new OrderCreatedEvent(TYPE, order.getId(), order.getCustomer().getId(),
                order.getTotalPrice(), order.getItems().size(), OffsetDateTime.now());
    }
}
