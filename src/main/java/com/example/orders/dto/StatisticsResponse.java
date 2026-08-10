package com.example.orders.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.example.orders.entity.OrderStatus;

/**
 * Aggregate figures for the ADMIN dashboard.
 *
 * @param ordersByStatus  count per status, with every status present even at zero - a client
 *                        rendering a chart should not have to guess which keys are missing
 * @param revenueByStatus money per status
 * @param totalOrders     across all statuses
 * @param totalRevenue    excluding cancelled orders, which are not revenue
 * @param averageOrderValue mean of non-cancelled orders, zero when there are none
 */
public record StatisticsResponse(
        Map<OrderStatus, Long> ordersByStatus,
        Map<OrderStatus, BigDecimal> revenueByStatus,
        long totalOrders,
        BigDecimal totalRevenue,
        BigDecimal averageOrderValue) {
}
