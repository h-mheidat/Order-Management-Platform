package com.example.orders.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.example.orders.entity.OrderStatus;

/**
 * An order without its line items, for list endpoints.
 *
 * <p>A separate, smaller type from {@link OrderResponse} for a concrete reason. Returning items in a
 * list would mean either one extra query per order - the N+1 in doc section 16 - or a JOIN FETCH
 * combined with pagination, which Hibernate can only satisfy by loading every matching row and
 * paginating in memory. {@code fail_on_pagination_over_collection_fetch} is enabled precisely so that
 * attempt fails loudly instead of quietly loading the table.
 */
public record OrderSummaryResponse(
        Long id,
        Long customerId,
        OrderStatus status,
        BigDecimal totalPrice,
        OffsetDateTime createdAt) {
}
