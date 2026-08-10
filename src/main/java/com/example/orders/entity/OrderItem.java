package com.example.orders.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * A single line of an order: how many of one product, at what price.
 *
 * <p>Not an aggregate root - always created through {@link Order#addItem(OrderItem)} and always
 * persisted by cascade from its order.
 */
@Entity
@Table(name = "order_items")
@Getter
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_items_seq")
    @SequenceGenerator(name = "order_items_seq", sequenceName = "order_items_seq",
            allocationSize = 50)
    private Long id;

    /** The owning side: this is the column Hibernate actually writes. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_items_order"))
    private Order order;

    /**
     * Identifier in the external product service, not a foreign key. Products live in another
     * system (stage 8 reaches it over WebClient), so there is nothing local to reference.
     */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * The price when the order was placed, copied from the product service - never re-read later.
     * An order's total must not change because a product was repriced afterwards.
     */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItem() {
        // Required by JPA.
    }

    public OrderItem(Long productId, int quantity, BigDecimal unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /** Package-private: only {@link Order#addItem(OrderItem)} may set the back-reference. */
    void setOrder(Order order) {
        this.order = order;
    }

    /**
     * Line total, {@code unitPrice * quantity}.
     *
     * <p>Not a persisted column: a stored value that can disagree with its inputs is a bug waiting
     * to happen. Stage 6 sums these with a Stream reduce to get the order total.
     */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OrderItem item)) {
            return false;
        }
        return id != null && id.equals(item.getId());
    }

    @Override
    public int hashCode() {
        return OrderItem.class.hashCode();
    }

    @Override
    public String toString() {
        // Does not touch `order`: it is lazy, and it would recurse back into this item.
        return "OrderItem{id=" + id + ", productId=" + productId + ", quantity=" + quantity
                + ", unitPrice=" + unitPrice + '}';
    }
}
