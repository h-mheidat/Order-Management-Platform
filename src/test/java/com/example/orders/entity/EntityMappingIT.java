package com.example.orders.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.orders.support.Containers;

/**
 * Verifies the stage 2 persistence model against a real PostgreSQL.
 *
 * <p>Two things are being checked. First, that the mappings and the Flyway migration agree - with
 * {@code ddl-auto: validate}, simply reaching a test body proves Hibernate accepted every column,
 * type and sequence. Second, that the behaviour the later stages depend on actually works: cascade,
 * orphan removal, optimistic-lock version bumps, and the database constraints that back up the
 * service layer.
 *
 * <p>{@code @DataJpaTest} rolls each test back, so the tests do not interfere with one another.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingIT {

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        Containers.registerTo(registry);
    }

    @Autowired
    EntityManager em;

    /**
     * Asserts that a database constraint - named, not just "something failed" - rejected the write.
     *
     * <p>The expected type is Hibernate's own {@link ConstraintViolationException}, not Spring's
     * {@code DataIntegrityViolationException}: exception translation happens in the Spring Data
     * repository proxy, and these tests go straight through the {@code EntityManager}. Asserting
     * the constraint name matters because every one of these tests would also pass against a
     * completely different violation.
     */
    private static void assertViolates(String constraintName, ThrowingCallable write) {
        assertThatThrownBy(write)
                .as("expected the database to reject this write via %s", constraintName)
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining(constraintName);
    }

    private User persistCustomer(String suffix) {
        User user = new User("ahmad" + suffix, "Ahmad" + suffix + "@Test.com", "{bcrypt}$2a$10$hash",
                Role.CUSTOMER);
        em.persist(user);
        return user;
    }

    @Test
    void persistsAnOrderWithItsItemsByCascade() {
        User customer = persistCustomer("1");

        Order order = new Order(customer);
        order.addItem(new OrderItem(10L, 2, new BigDecimal("25.50")));
        order.addItem(new OrderItem(20L, 1, new BigDecimal("99.99")));
        order.setTotalPrice(new BigDecimal("150.99"));
        em.persist(order);

        // Force the INSERTs, then clear so the assertions read from the database rather than from
        // the persistence context - otherwise this would only be testing Java object identity.
        em.flush();
        em.clear();

        Order reloaded = em.find(Order.class, order.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        // 150.99 equals 150.99 only if the scale survived the round trip; isEqualByComparingTo
        // would hide a numeric(19,2) that had silently become numeric(19,0).
        assertThat(reloaded.getTotalPrice()).isEqualTo(new BigDecimal("150.99"));
        assertThat(reloaded.getItems()).hasSize(2);
        assertThat(reloaded.getItems())
                .extracting(OrderItem::lineTotal)
                .containsExactlyInAnyOrder(new BigDecimal("51.00"), new BigDecimal("99.99"));
        assertThat(reloaded.getCustomer().getId()).isEqualTo(customer.getId());
    }

    @Test
    void lowercasesEmailSoTheCaseInsensitiveUniqueIndexHolds() {
        User user = persistCustomer("2");
        em.flush();

        assertThat(user.getEmail()).isEqualTo("ahmad2@test.com");

        User duplicateInDifferentCase =
                new User("other", "AHMAD2@TEST.COM", "{bcrypt}$2a$10$hash", Role.CUSTOMER);
        em.persist(duplicateInDifferentCase);

        assertViolates("uq_users_email_lower", em::flush);
    }

    @Test
    void incrementsVersionOnUpdateSoConcurrentEditsAreDetectable() {
        User customer = persistCustomer("3");
        Order order = new Order(customer);
        order.addItem(new OrderItem(10L, 1, new BigDecimal("10.00")));
        em.persist(order);
        em.flush();

        assertThat(order.getVersion()).isZero();

        order.setStatus(OrderStatus.CONFIRMED);
        em.flush();

        assertThat(order.getVersion()).isEqualTo(1L);
    }

    @Test
    void deletesTheRowWhenAnItemIsRemovedFromTheOrder() {
        User customer = persistCustomer("4");
        Order order = new Order(customer);
        OrderItem toRemove = new OrderItem(10L, 1, new BigDecimal("10.00"));
        order.addItem(toRemove);
        order.addItem(new OrderItem(20L, 1, new BigDecimal("20.00")));
        em.persist(order);
        em.flush();

        order.removeItem(toRemove);
        em.flush();
        em.clear();

        Long remaining = em.createQuery(
                        "select count(i) from OrderItem i where i.order.id = :id", Long.class)
                .setParameter("id", order.getId())
                .getSingleResult();
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    void rejectsTheSameProductTwiceInOneOrder() {
        User customer = persistCustomer("5");
        Order order = new Order(customer);
        order.addItem(new OrderItem(10L, 1, new BigDecimal("10.00")));
        order.addItem(new OrderItem(10L, 3, new BigDecimal("10.00")));
        em.persist(order);

        assertViolates("uq_order_items_order_product", em::flush);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        User customer = persistCustomer("6");
        Order order = new Order(customer);
        order.addItem(new OrderItem(10L, 0, new BigDecimal("10.00")));
        em.persist(order);

        assertViolates("ck_order_items_quantity", em::flush);
    }

    @Test
    void storesTheOutboxPayloadAsJsonbAndTracksPublication() {
        OutboxEvent event = new OutboxEvent("Order", "100", "ORDER_CREATED",
                "{\"orderId\":100,\"customerId\":5}");
        em.persist(event);
        em.flush();
        em.clear();

        OutboxEvent reloaded = em.find(OutboxEvent.class, event.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getEventId()).isInstanceOf(UUID.class);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getPayload()).contains("\"orderId\": 100");

        reloaded.markPublished(OffsetDateTime.now());
        em.flush();

        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void rejectsAPublishedOutboxRowWithNoPublishedAtTimestamp() {
        OutboxEvent event = new OutboxEvent("Order", "101", "ORDER_CREATED", "{}");
        em.persist(event);
        em.flush();

        // markPublished() sets both fields together; this reaches around it to prove the database
        // is the thing enforcing the invariant, not just the entity's API. The native statement is
        // rejected as it executes - there is no later flush to fail.
        assertViolates("ck_outbox_events_published_at", () ->
                em.createNativeQuery("update outbox_events set status = 'PUBLISHED' where id = :id")
                        .setParameter("id", event.getId())
                        .executeUpdate());
    }

    @Test
    void rejectsADuplicateProcessedEventId() {
        String eventId = UUID.randomUUID().toString();
        em.persist(new ProcessedEvent(eventId, "ORDER_CREATED"));
        em.flush();
        em.clear();

        // This is the idempotency guarantee stage 12 relies on: the second delivery of the same
        // event cannot be recorded, so its side effect cannot commit either.
        em.persist(new ProcessedEvent(eventId, "ORDER_CREATED"));

        assertViolates("pk_processed_events", em::flush);
    }
}
