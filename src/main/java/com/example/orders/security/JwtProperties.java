package com.example.orders.security;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing configuration.
 *
 * <p>There is deliberately no default secret. A committed fallback value is the single most common
 * way a signing key reaches production: everything works in development, nobody sets the variable,
 * and the deployed service happily signs tokens with a key that is public on GitHub. Without
 * {@code JWT_SECRET} the application refuses to start, which is a five-second problem instead of a
 * silent forgery vulnerability.
 *
 * @param secret     HMAC-SHA256 key. At least 32 bytes: HS256 truncates nothing, but a shorter key
 *                   simply has less entropy than the algorithm's output and is brute-forceable.
 * @param issuer     the {@code iss} claim, validated on the way back in
 * @param expiration how long an access token stays valid
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
        @NotBlank String issuer,
        Duration expiration) {

    public JwtProperties {
        if (expiration == null) {
            // Short-lived by default. A stolen token cannot be revoked - the only real mitigation
            // is that it stops working quickly. Refresh tokens are a separate concern.
            expiration = Duration.ofMinutes(15);
        }
    }
}
