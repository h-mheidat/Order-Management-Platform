package com.example.orders.kafka;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names.
 *
 * <p>Configurable rather than hardcoded so a shared cluster can be prefixed per environment - the
 * alternative is a staging deployment quietly consuming production's topic.
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicProperties(@NotBlank String orders, String ordersDeadLetter) {

    public KafkaTopicProperties {
        if (ordersDeadLetter == null || ordersDeadLetter.isBlank()) {
            ordersDeadLetter = orders + ".DLT";
        }
    }
}
