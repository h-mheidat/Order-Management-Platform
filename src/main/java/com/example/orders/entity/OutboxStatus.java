package com.example.orders.entity;

/**
 * Publication state of an outbox row.
 *
 * <p>{@code FAILED} is not "give up": it marks rows the publisher could not send after its retry
 * budget, so they stay visible for alerting and manual replay instead of being deleted.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
