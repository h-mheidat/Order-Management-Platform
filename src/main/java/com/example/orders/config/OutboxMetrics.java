package com.example.orders.config;

import com.example.orders.entity.OutboxStatus;
import com.example.orders.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics that answer "is the outbox actually draining?".
 *
 * <p>This is the alert that matters for the whole event pipeline. Order creation succeeds whether or not
 * events are being published, which is exactly the property the outbox was built for - and exactly why
 * a broken publisher is invisible from the outside. Nothing fails, no error rate moves, and consumers
 * simply stop hearing about orders.
 *
 * <p>A growing PENDING count is the signal. A non-zero FAILED count means events have been parked and
 * need a human.
 */
@Configuration
public class OutboxMetrics {

    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, OutboxEventRepository outboxRepository) {
        return Gauge.builder("orders.outbox.pending",
                        () -> outboxRepository.countByStatus(OutboxStatus.PENDING))
                .description("Events waiting to be published. Sustained growth means the publisher "
                        + "is not keeping up or cannot reach the broker.")
                .register(registry);
    }

    @Bean
    Gauge outboxFailedGauge(MeterRegistry registry, OutboxEventRepository outboxRepository) {
        return Gauge.builder("orders.outbox.failed",
                        () -> outboxRepository.countByStatus(OutboxStatus.FAILED))
                .description("Events parked after exhausting their retries. Any non-zero value needs "
                        + "investigation - these will never be delivered on their own.")
                .register(registry);
    }
}
