package com.example.orders.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for the staff accounts created at startup.
 *
 * <p>Registration only ever produces a CUSTOMER - accepting a role from the request body would be
 * privilege escalation through an over-trusting DTO. So ADMIN and SUPPORT have to come from somewhere
 * else, and without this there is no legitimate way to create them at all.
 *
 * <p>Everything here comes from the environment and nothing has a default password. That is the same rule
 * as {@code JwtProperties}, for the same reason: a committed default is how a known-password
 * administrator reaches production. Turning seeding on without supplying a password fails startup.
 *
 * @param enabled off unless explicitly switched on. Seeding is a development convenience, so the safe
 *                state is the one you get by doing nothing.
 */
@Validated
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(boolean enabled, @Valid Account admin, @Valid Account support) {

    /**
     * One seeded account.
     *
     * @param password minimum length is enforced so a developer cannot seed {@code "1"} and then
     *                 reuse that habit somewhere it matters. Capped at 72 because BCrypt ignores
     *                 anything beyond that.
     */
    public record Account(
            @NotBlank String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }
}
