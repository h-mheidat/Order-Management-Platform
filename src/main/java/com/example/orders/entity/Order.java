package com.example.orders.entity;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * A customer order and its line items.
 *
 * <p>Aggregate root: order items have no lifecycle of their own and are only ever reached through
 * the order that owns them. That is why they cascade from here and why there is no separate
 * repository for them.
 */
@Entity
@Table(name = "orders")
@Getter
public class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orders_seq")
    @SequenceGenerator(name = "orders_seq", sequenceName = "orders_seq", allocationSize = 50)
    private Long id;

    /**
     * LAZY, like every association in this model. EAGER would load the customer on every single
     * order read whether or not anyone needs it, and turns a list of 50 orders into a join nobody
     * asked for. Stage 16 fetches it explicitly with JOIN FETCH where it is actually required.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_orders_customer"))
    private User customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    /**
     * BigDecimal, never double. Binary floating point cannot represent 0.10, so money arithmetic
     * done in double is wrong by design - and wrong in a way that only shows up in totals.
     */
    @Column(name = "total_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    /**
     * Optimistic locking. Hibernate appends {@code AND version = ?} to every update and raises
     * {@link jakarta.persistence.OptimisticLockException} when no row matched, which is how two
     * concurrent status updates on the same order are detected rather than silently lost.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * A {@code Set}, not a {@code List}. A mapped {@code List} is a Hibernate bag: removing one
     * element makes it delete every row for the order and reinsert the survivors. With a Set,
     * removal is a single delete.
     *
     * <p>{@code orphanRemoval} means dropping an item from this collection deletes its row -
     * matching {@code ON DELETE CASCADE} on the foreign key.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private Set<OrderItem> items = new LinkedHashSet<>();

    protected Order() {
        // Required by JPA.
    }

    public Order(User customer) {
        this.customer = customer;
    }

    /**
     * Adds a line item and keeps both ends of the association in sync.
     *
     * <p>Setting only one side is the most common bidirectional-mapping bug: the collection looks
     * right in memory, then the insert fails on a null {@code order_id} because the owning side -
     * the {@code @ManyToOne} - is what Hibernate actually writes.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }

    /** Unmodifiable: items are added through {@link #addItem(OrderItem)} so the sync cannot be skipped. */
    public Set<OrderItem> getItems() {
        return Collections.unmodifiableSet(items);
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Order order)) {
            return false;
        }
        return id != null && id.equals(order.getId());
    }

    @Override
    public int hashCode() {
        return Order.class.hashCode();
    }

    @Override
    public String toString() {
        // Deliberately does not touch customer or items: both are lazy, and a toString that
        // triggers a fetch turns logging into N+1 queries - or a LazyInitializationException.
        return "Order{id=" + id + ", status=" + status + ", totalPrice=" + totalPrice
                + ", version=" + version + '}';
    }
}
