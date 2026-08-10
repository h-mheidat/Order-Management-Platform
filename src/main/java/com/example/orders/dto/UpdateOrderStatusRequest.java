package com.example.orders.dto;

import com.example.orders.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** SUPPORT/ADMIN status change. Whether the transition is legal is decided by {@link OrderStatus}. */
public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
}
