package com.example.orders.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One requested line item.
 *
 * <p>No price: the client says what and how many, the server decides what it costs. A
 * client-supplied price is a client-supplied discount.
 */
public record OrderItemRequest(

        @NotNull Long productId,

        // Both bounds matter. Zero or negative would be nonsense the check constraint would reject
        // as a 500; the upper bound stops a typo'd quantity from becoming a six-figure order.
        @NotNull @Min(1) @Max(1000) Integer quantity) {
}
