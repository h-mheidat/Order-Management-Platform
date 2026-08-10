package com.example.orders.kafka;

import com.example.orders.kafka.OrderEventConsumer.DuplicateEventException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when a consumer throws.
 *
 * <p>The default behaviour - retry a few times, then log and move on - loses the message. This routes
 * anything that cannot be handled to a dead letter topic instead, so nothing disappears silently and a
 * poison message cannot block its partition forever.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template,
                                          KafkaTopicProperties topics) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                // Same partition number on the dead letter topic as the original, so a failed record
                // can be traced back to where it came from.
                (record, exception) -> {
                    log.error("Routing record from {}-{} offset {} to {} after repeated failures",
                            record.topic(), record.partition(), record.offset(),
                            topics.ordersDeadLetter(), exception);
                    return new org.apache.kafka.common.TopicPartition(
                            topics.ordersDeadLetter(), record.partition());
                });

        // Exponential backoff with a cap: a broker-side or downstream blip deserves a few spaced
        // retries, but a genuinely broken message must reach the dead letter topic in seconds rather
        // than holding its partition for minutes.
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(4);
        backOff.setMaxInterval(4000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Never retried, because retrying cannot change the outcome:
        handler.addNotRetryableExceptions(
                // The bytes cannot be turned into an envelope. They will not parse next time either.
                SerializationException.class,
                ConversionException.class,
                // No eventId, so it cannot be de-duplicated.
                IllegalArgumentException.class,
                // Already handled by another consumer; a retry would hit the same primary key.
                DuplicateEventException.class);

        return handler;
    }
}
