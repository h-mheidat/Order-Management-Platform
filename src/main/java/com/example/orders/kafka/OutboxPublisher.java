package com.example.orders.kafka;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.orders.entity.OutboxEvent;
import com.example.orders.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox to Kafka.
 *
 * <p>This is the second half of the pattern. Order creation committed the order and its event together
 * in one local transaction; this publishes what that transaction left behind. Kafka being down
 * therefore delays events - it never loses them, and it never stops an order from being placed.
 *
 * <h2>Running on every instance</h2>
 *
 * <p>Every instance polls, and that is deliberate. A single elected publisher is simpler right up to the
 * moment that instance dies, at which point publishing stops until something notices. Concurrency is
 * made safe by {@code FOR UPDATE SKIP LOCKED} in the claiming query, not by arranging for only one
 * poller to exist.
 *
 * <h2>Why duplicates are possible, and why that is acceptable</h2>
 *
 * <p>A send can succeed and the transaction can then fail to commit - the process is killed, the
 * database connection drops - leaving the row PENDING and the event already on the topic. The next poll
 * republishes it. This is at-least-once delivery, and it is the reason consumers must be idempotent
 * rather than an accident to be engineered away. Exactly-once here would need a transaction spanning
 * PostgreSQL and Kafka, which is the distributed transaction the outbox pattern exists to avoid.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * Small on purpose. The claiming transaction holds row locks while sending, so the batch size
     * bounds how long those locks are held and how much work is lost if the instance dies mid-poll.
     */
    private static final int BATCH_SIZE = 50;

    /** Bounded so a hung broker cannot hold the transaction and its locks open indefinitely. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    /**
     * After this many failures a row is parked as FAILED rather than retried forever.
     *
     * <p>A permanently unpublishable event - a payload no consumer can read, a topic that no longer
     * exists - would otherwise be retried on every poll for the life of the system, and its repeated
     * failure would hide every other problem in the log.
     */
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topics;
    private final ObjectMapper objectMapper;
    private final Counter published;
    private final Counter failed;

    OutboxPublisher(OutboxEventRepository outboxRepository,
                    KafkaTemplate<String, Object> kafkaTemplate,
                    KafkaTopicProperties topics, ObjectMapper objectMapper,
                    MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
        this.published = Counter.builder("orders.outbox.published")
                .description("Events successfully sent to Kafka")
                .register(registry);
        this.failed = Counter.builder("orders.outbox.send.failures")
                .description("Send attempts that failed and will be retried")
                .register(registry);
    }

    /**
     * Publishes one batch.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end of the previous
     * run, so a slow poll cannot overlap with the next one and pile up.
     *
     * <p>{@code REQUIRES_NEW} so this never joins a caller's transaction - it is a background job with
     * its own boundary, and a scheduled method that silently enlists in something else is very hard to
     * reason about.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.claimPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox event(s)", batch.size());
        int published = 0;
        for (OutboxEvent event : batch) {
            if (publish(event)) {
                published++;
            }
        }
        if (published != batch.size()) {
            log.warn("Published {} of {} outbox events; the rest will be retried",
                    published, batch.size());
        }
    }

    private boolean publish(OutboxEvent event) {
        try {
            EventEnvelope envelope = EventEnvelope.from(event, objectMapper.readTree(event.getPayload()));

            // The aggregate id is the message key, so every event for one order lands on the same
            // partition and is therefore delivered in order. Kafka guarantees ordering per partition
            // and nothing across partitions - keying by event id instead would scatter an order's
            // history across the topic and let CANCELLED arrive before CONFIRMED.
            kafkaTemplate.send(topics.orders(), event.getAggregateId(), envelope)
                    // Waited on deliberately: the point is to learn whether the broker accepted the
                    // record before marking the row published. Firing and forgetting would mark rows
                    // as sent that never arrived.
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            event.markPublished(OffsetDateTime.now());
            published.increment();
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (JsonProcessingException e) {
            // The stored payload is not valid JSON. Retrying cannot help, so park it immediately
            // rather than burning the retry budget on something that will never parse.
            log.error("Outbox event {} has an unreadable payload and will not be retried",
                    event.getEventId(), e);
            event.markFailed();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(event, e);
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, Exception cause) {
        failed.increment();
        event.recordFailedAttempt();
        if (event.getAttempts() >= MAX_ATTEMPTS) {
            log.error("Outbox event {} failed {} times and is being parked as FAILED",
                    event.getEventId(), event.getAttempts(), cause);
            event.markFailed();
        } else {
            log.warn("Outbox event {} failed (attempt {}), will retry: {}",
                    event.getEventId(), event.getAttempts(), cause.toString());
        }
    }
}
