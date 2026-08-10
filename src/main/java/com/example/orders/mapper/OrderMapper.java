package com.example.orders.mapper;

import java.util.Comparator;
import java.util.List;

import com.example.orders.dto.OrderItemResponse;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO conversion for orders.
 *
 * <p>Hand-written rather than generated: at this size a mapping library adds an annotation processor
 * and buys nothing, and being explicit is what keeps the customer's email, password hash and the rest
 * of the {@code User} entity out of an order response.
 */
@Component
public class OrderMapper {

    /**
     * Full detail, including items.
     *
     * <p>Only safe to call on an order whose items are already loaded - see
     * {@code OrderRepository.findWithItemsById}. Calling it on a lazily-loaded order outside a
     * transaction raises {@code LazyInitializationException}, which with {@code open-in-view}
     * disabled happens in development rather than becoming a hidden query in production.
     */
    public OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                // Stable ordering. The persistence collection is a set, so without this the item
                // order in the JSON could differ between two reads of the same order.
                .sorted(Comparator.comparing(OrderItem::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toItemResponse)
                .toList();

        return new OrderResponse(
                order.getId(),
                // getCustomer() is a lazy proxy; reading only its id does not trigger a query,
                // because the foreign key value is already in the proxy.
                order.getCustomer().getId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                items);
    }

    /** List view: no items, so no N+1 and no in-memory pagination. */
    public OrderSummaryResponse toSummary(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getCustomer().getId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getCreatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getQuantity(),
                item.getUnitPrice(), item.lineTotal());
    }
}
