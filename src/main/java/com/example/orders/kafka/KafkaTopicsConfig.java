package com.example.orders.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this application owns.
 *
 * <p>Declared explicitly rather than relying on {@code auto.create.topics.enable}. Auto-creation gives
 * every typo'd topic name a working topic - the producer succeeds, the consumer waits forever on a name
 * nobody is writing to, and nothing looks broken. It also creates topics with the broker's defaults
 * rather than the partition count and replication this data needs.
 *
 * <p>{@code KafkaAdmin} applies these at startup. It creates missing topics and never destroys or
 * shrinks an existing one, so this is safe to run against an environment where the topics already exist.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaTopicsConfig {

    /**
     * Three partitions, not one.
     *
     * <p>Partitions are the unit of consumer parallelism: a single-partition topic can only ever be
     * read by one consumer in a group, however many instances are deployed. Raising the count later is
     * possible but changes which partition a key hashes to, and therefore breaks per-key ordering for
     * keys already in flight - so it is worth getting roughly right at creation.
     */
    private static final int PARTITIONS = 3;

    /**
     * One replica, because this is a single-broker development cluster.
     *
     * <p>In production this must be at least 3 with {@code min.insync.replicas=2}: a topic with one
     * replica loses data permanently when its broker's disk does.
     */
    private static final short REPLICAS = 1;

    @Bean
    NewTopic ordersTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name(properties.orders())
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * Dead letter topic for messages the consumer could not handle.
     *
     * <p>Without one, a message that always fails is retried forever and blocks its partition - every
     * later message behind it waits on a record that will never succeed. This is where they go instead,
     * so the partition keeps moving and the failure becomes something to investigate rather than an
     * outage.
     */
    @Bean
    NewTopic ordersDeadLetterTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name(properties.ordersDeadLetter())
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
