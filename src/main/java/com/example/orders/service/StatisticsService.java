package com.example.orders.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.orders.dto.StatisticsResponse;
import com.example.orders.entity.OrderStatus;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OrderRepository.StatusAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate figures for ADMIN.
 *
 * <p>The counting and summing happen in the database - one row per status - and the Streams work here
 * only reshapes that handful of rows. The tempting alternative, {@code findAll()} then
 * {@code groupingBy}, produces identical numbers and moves the entire orders table across the network
 * to do it: fine with a hundred orders, an outage with a million.
 */
@Service
public class StatisticsService {

    private final OrderRepository orderRepository;

    StatisticsService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public StatisticsResponse statistics() {
        List<StatusAggregate> aggregates = orderRepository.aggregateByStatus();

        Map<OrderStatus, Long> ordersByStatus = aggregates.stream()
                .collect(Collectors.toMap(StatusAggregate::getStatus, StatusAggregate::getCount,
                        // No duplicate keys are possible from a GROUP BY, but a merge function is
                        // required by the collector and throwing makes a broken query obvious rather
                        // than silently keeping one of two values.
                        (first, second) -> {
                            throw new IllegalStateException("duplicate status in aggregate");
                        },
                        // EnumMap: iteration follows the enum's declaration order, so the JSON comes
                        // out in lifecycle order rather than hash order.
                        () -> new EnumMap<>(OrderStatus.class)));

        Map<OrderStatus, BigDecimal> revenueByStatus = aggregates.stream()
                .collect(Collectors.toMap(StatusAggregate::getStatus, StatusAggregate::getRevenue,
                        (first, second) -> first, () -> new EnumMap<>(OrderStatus.class)));

        // Every status present, including the ones with no orders.
        for (OrderStatus status : OrderStatus.values()) {
            ordersByStatus.putIfAbsent(status, 0L);
            revenueByStatus.putIfAbsent(status, BigDecimal.ZERO);
        }

        long totalOrders = ordersByStatus.values().stream().mapToLong(Long::longValue).sum();

        // Cancelled orders are not revenue. Summing every status would report money that will never
        // arrive - the kind of number that reads fine on a dashboard and is simply wrong.
        long billableOrders = ordersByStatus.entrySet().stream()
                .filter(entry -> entry.getKey() != OrderStatus.CANCELLED)
                .mapToLong(Map.Entry::getValue)
                .sum();
        BigDecimal totalRevenue = revenueByStatus.entrySet().stream()
                .filter(entry -> entry.getKey() != OrderStatus.CANCELLED)
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageOrderValue = billableOrders == 0
                // Guarding division by zero explicitly: an empty system is a normal state, not an
                // error, and it is the state the dashboard is in on day one.
                ? BigDecimal.ZERO
                : totalRevenue.divide(BigDecimal.valueOf(billableOrders), 2, RoundingMode.HALF_UP);

        return new StatisticsResponse(ordersByStatus, revenueByStatus, totalOrders, totalRevenue,
                averageOrderValue);
    }
}
