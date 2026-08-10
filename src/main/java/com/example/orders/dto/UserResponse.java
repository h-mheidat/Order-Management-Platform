package com.example.orders.dto;

import com.example.orders.entity.Role;

/**
 * A user as the API exposes them.
 *
 * <p>A separate type from the {@code User} entity specifically so that adding a field to the entity
 * cannot leak it. Returning entities is how password hashes end up in responses.
 */
public record UserResponse(Long id, String username, String email, Role role) {
}
