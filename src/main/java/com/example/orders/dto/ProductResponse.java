package com.example.orders.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A product as the external Product Service describes it.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is not laziness: it is what lets the
 * upstream service add a field without breaking this consumer. Failing on unknown properties couples
 * our deploys to theirs.
 *
 * @param price the current catalogue price. Copied onto the order line at creation time and never
 *              read again for that order - see {@code OrderItem.unitPrice}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductResponse(Long id, String name, BigDecimal price, boolean available) {
}
