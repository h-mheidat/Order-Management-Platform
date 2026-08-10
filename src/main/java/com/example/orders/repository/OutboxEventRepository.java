package com.example.orders.repository;

import java.util.List;

import com.example.orders.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished events for this instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the publisher safe to run on every instance at
     * once - which it has to be, because a leader-elected singleton publisher stops publishing the
     * moment that one instance dies.
     *
     * <p>Plain {@code FOR UPDATE} would serialise the instances: the second would block until the first
     * committed, then find the same rows already published. {@code SKIP LOCKED} instead hands the second
     * instance the next unlocked rows, so three instances drain three disjoint batches concurrently and
     * no event is published twice by different instances.
     *
     * <p>Native SQL because JPQL has no way to express SKIP LOCKED. {@code @Lock(PESSIMISTIC_WRITE)}
     * gets as far as FOR UPDATE and then blocks, which is the behaviour being avoided.
     *
     * <p>Ordered by {@code created_at} so events are published roughly in the order they happened, and
     * limited so one poll cannot hold locks on the whole backlog.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("batchSize") int batchSize);

    long countByStatus(com.example.orders.entity.OutboxStatus status);
}
