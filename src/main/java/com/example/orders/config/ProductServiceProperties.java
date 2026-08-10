package com.example.orders.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for the external Product Service.
 *
 * @param baseUrl         where it lives
 * @param connectTimeout  how long to wait for a TCP connection
 * @param responseTimeout the network-level read timeout. Kept below the Resilience4j time limiter so
 *                        the socket gives up first and the connection is released, rather than the
 *                        limiter abandoning a request that is still holding a connection open.
 */
@Validated
@ConfigurationProperties(prefix = "app.product-service")
public record ProductServiceProperties(
        @NotBlank String baseUrl,
        Duration connectTimeout,
        Duration responseTimeout) {

    public ProductServiceProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(1);
        }
        if (responseTimeout == null) {
            responseTimeout = Duration.ofSeconds(2);
        }
    }
}
