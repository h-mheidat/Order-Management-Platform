package com.example.orders.dto;

import java.math.BigDecimal;

/**
 * A line item as returned by the API.
 *
 * @param lineTotal computed, not stored - a persisted total that can disagree with quantity times
 *                  price is a bug waiting to be discovered on an invoice
 */
public record OrderItemResponse(
        Long id,
        Long productId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {
}
