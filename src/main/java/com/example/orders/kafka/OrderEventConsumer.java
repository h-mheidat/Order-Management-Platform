package com.example.orders.kafka;

import com.example.orders.entity.ProcessedEvent;
import com.example.orders.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes order events, exactly once in effect if not in delivery.
 *
 * <h2>The idempotency mechanism</h2>
 *
 * <p>Kafka delivers at least once, and the outbox publisher can republish an event whose send succeeded
 * but whose transaction did not commit. So {@code ORDER_CREATED 100} arriving twice is normal, not
 * exceptional, and handling it twice would send two notifications or write two audit rows.
 *
 * <p>The side effect and the {@code processed_events} insert happen in <b>one transaction</b>. That is
 * the whole trick: they either both commit or both roll back, so it is impossible to act on an event and
 * fail to record that it was acted on - or to record it and fail to act.
 *
 * <p>The {@code existsById} check in front is only an optimisation. The guarantee is the primary key: if
 * two consumers somehow process the same event concurrently - which a rebalance can cause - one insert
 * wins and the other violates the key, and its whole transaction including the side effect rolls back. A
 * check-then-act without that constraint has a window between the two steps where both callers see
 * nothing and both proceed.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ProcessedEventRepository processedEvents;

    OrderEventConsumer(ProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    @KafkaListener(topics = "${app.kafka.topics.orders}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void consume(EventEnvelope envelope,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        if (envelope.eventId() == null) {
            // Unidentifiable, so it cannot be de-duplicated. Dropping it is better than processing it
            // an unknown number of times; the error handler routes it to the dead letter topic.
            throw new IllegalArgumentException(
                    "Event has no eventId and cannot be handled idempotently");
        }

        String eventId = envelope.eventId().toString();
        if (processedEvents.existsById(eventId)) {
            log.debug("Skipping already-processed event {} ({} partition={} offset={})",
                    eventId, envelope.eventType(), partition, offset);
            return;
        }

        try {
            handle(envelope);
            // Same transaction as the side effect above. saveAndFlush so the key violation surfaces
            // here, where it can be recognised as a duplicate, rather than at commit.
            processedEvents.saveAndFlush(new ProcessedEvent(eventId, envelope.eventType()));
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent consumer of the same event. The side effect in this
            // transaction rolls back with it, so it happened exactly once overall.
            log.info("Event {} was processed concurrently by another consumer; discarding this copy",
                    eventId);
            throw new DuplicateEventException(eventId, e);
        }
    }

    /**
     * The actual work. Notification and audit, per doc section 10.
     *
     * <p>Kept trivial on purpose: this stage is about delivery semantics, and an audit line is enough to
     * make double-processing visible if the idempotency were broken.
     */
    private void handle(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case OrderCreatedEvent.TYPE -> log.info(
                    "AUDIT order.created orderId={} customerId={} total={} eventId={}",
                    envelope.payload().path("orderId").asText(),
                    envelope.payload().path("customerId").asText(),
                    envelope.payload().path("totalPrice").asText(),
                    envelope.eventId());
            // Unknown types are ignored rather than treated as errors: a producer may start emitting a
            // new type before this consumer knows about it, and failing would send perfectly good
            // events to the dead letter topic.
            default -> log.debug("Ignoring unhandled event type {}", envelope.eventType());
        }
    }

    /**
     * Signals that this copy of an event lost a race and must not be retried.
     *
     * <p>A distinct type so the error handler can route it straight to the dead letter topic instead of
     * retrying - the retry would hit the same primary key and fail identically.
     */
    static class DuplicateEventException extends RuntimeException {
        DuplicateEventException(String eventId, Throwable cause) {
            super("Event " + eventId + " has already been processed", cause);
        }
    }
}
