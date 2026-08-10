package com.example.orders.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.example.orders.entity.OrderStatus;

/** A single order in full, including its line items. Returned by the detail endpoint only. */
public record OrderResponse(
        Long id,
        Long customerId,
        OrderStatus status,
        BigDecimal totalPrice,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<OrderItemResponse> items) {
}
