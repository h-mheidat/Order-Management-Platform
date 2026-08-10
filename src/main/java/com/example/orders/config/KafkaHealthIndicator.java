package com.example.orders.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reports Kafka broker reachability under {@code /actuator/health}.
 *
 * <p>Spring Boot ships health indicators for the {@code DataSource} and for Redis, but none for a
 * Kafka broker (only for Kafka Streams). Without this class, a single health call cannot answer the
 * one question stage 1 exists to answer: is the application actually connected to all three
 * backing services?
 *
 * <p>The check is deliberately cheap - a metadata request, no topic creation and no produce - and
 * hard-bounded by a timeout. A health endpoint that can hang is worse than no health endpoint: it
 * turns a degraded dependency into an unresponsive instance and, behind a load balancer, into an
 * outage.
 */
@Component("kafka")
class KafkaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final KafkaAdmin kafkaAdmin;

    KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        // A short-lived AdminClient per check rather than a shared one: it keeps no connection
        // open between polls, so a broker restart cannot leave this indicator wedged on a stale
        // connection and permanently reporting DOWN.
        try (AdminClient admin = AdminClient.create(Map.copyOf(kafkaAdmin.getConfigurationProperties()))) {
            DescribeClusterOptions options =
                    new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis());
            DescribeClusterResult cluster = admin.describeCluster(options);

            String clusterId = cluster.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int nodeCount = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodes", nodeCount)
                    .build();
        } catch (InterruptedException e) {
            // Never swallow an interrupt: restore the flag so the caller's cancellation still works.
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (ExecutionException e) {
            return Health.down(e.getCause() != null ? e.getCause() : e).build();
        } catch (TimeoutException | RuntimeException e) {
            return Health.down(e).build();
        }
    }
}
