package com.example.orders.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Order creation payload.
 *
 * <p>{@code @Valid} on the list is required, not decorative: without it the nested
 * {@link OrderItemRequest} constraints are never evaluated and a quantity of -5 reaches the service
 * layer. It is a silent failure - validation appears to be configured and simply is not running.
 *
 * <p>No customer id either. It comes from the authenticated token, so one customer cannot place an
 * order on another's account by editing the request body.
 */
public record CreateOrderRequest(

        @NotEmpty(message = "an order must contain at least one item")
        @Size(max = 100, message = "an order may contain at most 100 line items")
        @Valid
        List<OrderItemRequest> items) {
}
