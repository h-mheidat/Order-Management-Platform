package com.example.orders.entity;

import java.util.Set;

/**
 * Lifecycle of an order.
 *
 * <p>The legal transitions live here rather than in the service layer, so there is exactly one
 * definition of what may follow what. Stage 5 (order CRUD) and the SUPPORT status update endpoint
 * both consult {@link #canTransitionTo(OrderStatus)} instead of writing their own rules.
 */
public enum OrderStatus {

    CREATED,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /**
     * Whether this status may legally be followed by {@code next}.
     *
     * <p>{@link #DELIVERED} and {@link #CANCELLED} are terminal: nothing follows them. A delivered
     * order that can be cancelled is a refund problem, not a status change.
     */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED -> Set.of(CONFIRMED, CANCELLED).contains(next);
            case CONFIRMED -> Set.of(PROCESSING, CANCELLED).contains(next);
            case PROCESSING -> Set.of(SHIPPED, CANCELLED).contains(next);
            case SHIPPED -> Set.of(DELIVERED).contains(next);
            case DELIVERED, CANCELLED -> false;
        };
    }

    /** Whether a customer is still allowed to cancel an order in this status. */
    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }

    /** Whether the order has reached the end of its lifecycle. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
