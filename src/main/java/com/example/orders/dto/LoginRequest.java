package com.example.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login payload.
 *
 * <p>No {@code @Size} or pattern rules on the password here. Validating the format of a submitted
 * password tells an attacker which candidates are worth trying, and would reject an old password
 * that no longer meets a tightened policy with a confusing 400 instead of a clean 401.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
