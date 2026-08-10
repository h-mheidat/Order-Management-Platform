package com.example.orders.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import com.example.orders.entity.OrderStatus;
import com.example.orders.entity.Role;
import com.example.orders.entity.User;
import com.example.orders.repository.OrderRepository;
import com.example.orders.support.Containers;
import com.example.orders.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that two concurrent writers to the same order cannot silently overwrite each other.
 *
 * <p>This needs real threads and real transactions. The version column can be asserted in a single
 * thread - {@code EntityMappingIT} already does - but that only shows the number increments. The
 * property that matters is that a <em>lost update</em> becomes an error, and a lost update requires two
 * transactions overlapping in time. A sequential test would pass against a mapping with no
 * {@code @Version} at all.
 */
@SpringBootTest
@Import(TestUsers.class)
class OptimisticLockingIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerTo(registry);
    }

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TestUsers testUsers;

    @Autowired
    TransactionTemplate transactionTemplate;

    private Long persistOrder() {
        User customer = testUsers.create(Role.CUSTOMER);
        return transactionTemplate.execute(status -> {
            Order order = new Order(customer);
            order.addItem(new OrderItem(10L, 1, new BigDecimal("10.00")));
            order.setTotalPrice(new BigDecimal("10.00"));
            return orderRepository.save(order).getId();
        });
    }

    @Test
    void turnsTwoSimultaneousUpdatesIntoAConflictInsteadOfALostUpdate() throws Exception {
        Long orderId = persistOrder();

        // Both writers read the row, then both write. The barrier is what forces the overlap: without
        // it the first transaction usually commits before the second one reads, and the conflict this
        // test exists to detect never happens.
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        Callable<Void> update = () -> transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            try {
                bothHaveRead.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            order.setStatus(OrderStatus.CONFIRMED);
            // Explicit flush inside the transaction so the version check happens here rather than at
            // commit, where the exception would escape the TransactionTemplate differently.
            orderRepository.saveAndFlush(order);
            return null;
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Void>> results = executor.invokeAll(List.of(update, update));

            long failures = results.stream().filter(future -> {
                try {
                    future.get();
                    return false;
                } catch (Exception e) {
                    // Hibernate raises OptimisticLockException; Spring Data translates it. Which one
                    // arrives depends on the write path, so the assertion accepts either.
                    Throwable cause = rootCause(e);
                    assertThat(cause)
                            .isInstanceOfAny(OptimisticLockingFailureException.class,
                                    jakarta.persistence.OptimisticLockException.class,
                                    org.hibernate.StaleObjectStateException.class);
                    return true;
                }
            }).count();

            assertThat(failures)
                    .as("exactly one writer must win; the loser must be told, not silently discarded")
                    .isEqualTo(1);
        }

        // The winner's change is intact.
        Order finalState = orderRepository.findById(orderId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(finalState.getVersion()).isEqualTo(1L);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof jakarta.persistence.OptimisticLockException) {
                return current;
            }
        }
        return current;
    }
}
