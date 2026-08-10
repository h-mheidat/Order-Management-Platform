package com.example.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>Note what it does not contain: a role. Accepting one would let anyone register as ADMIN by
 * adding a field to the JSON - privilege escalation through an over-trusting DTO. Registration always
 * produces a CUSTOMER; staff accounts are provisioned deliberately.
 */
public record RegisterRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "may contain only letters, digits, dot, underscore and hyphen")
        String username,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        // Length first, composition second: length is what actually resists brute force. The cap
        // matters too - BCrypt ignores input beyond 72 bytes, so a longer password would be
        // silently truncated and give a false sense of strength.
        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "must contain at least one letter")
        @Pattern(regexp = ".*\\d.*", message = "must contain at least one digit")
        String password) {
}
