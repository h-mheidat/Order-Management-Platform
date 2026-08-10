package com.example.orders.entity;

/**
 * Application roles. Persisted as a string (never as an ordinal - reordering this enum would
 * silently rewrite everyone's role) and mirrored by a check constraint on {@code users.role}.
 */
public enum Role {
    CUSTOMER,
    SUPPORT,
    ADMIN
}
