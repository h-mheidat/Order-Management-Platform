package com.example.orders.repository;

import java.math.BigDecimal;
import java.util.Optional;

import com.example.orders.entity.Order;
import com.example.orders.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Loads one order with its items in a single query.
     *
     * <p>Without the entity graph this is two queries - one for the order, one for the collection the
     * moment anything touches it. With a list of orders it would be one per order: the N+1 of doc
     * section 16. {@code @EntityGraph} is preferred to a JOIN FETCH in JPQL here because it composes
     * with the derived query rather than replacing it.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    /**
     * A customer's own orders, newest first.
     *
     * <p>Returns entities without items on purpose - see {@code OrderSummaryResponse}. Combining a
     * collection fetch with pagination forces Hibernate to paginate in memory.
     */
    Page<Order> findAllByCustomerId(Long customerId, Pageable pageable);

    Page<Order> findAllByCustomerIdAndStatus(Long customerId, OrderStatus status, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus status, Pageable pageable);

    /**
     * Order counts grouped by status, computed by the database.
     *
     * <p>The alternative - loading every order and grouping in Java - moves the whole table across
     * the wire to count rows. Grouping belongs where the data is. The Streams work in
     * {@code StatisticsService} then operates on one row per status, not one per order.
     */
    @Query("select o.status as status, count(o) as count, coalesce(sum(o.totalPrice), 0) as revenue "
            + "from Order o group by o.status")
    java.util.List<StatusAggregate> aggregateByStatus();

    /** Projection for {@link #aggregateByStatus()}. */
    interface StatusAggregate {
        OrderStatus getStatus();

        long getCount();

        BigDecimal getRevenue();
    }

    @Query("select coalesce(sum(o.totalPrice), 0) from Order o where o.status <> :excluded")
    BigDecimal sumTotalPriceExcludingStatus(@Param("excluded") OrderStatus excluded);

    long countByCustomerId(Long customerId);
}
