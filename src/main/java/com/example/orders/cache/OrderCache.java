package com.example.orders.cache;

import com.example.orders.dto.OrderResponse;
import com.example.orders.exception.ResourceNotFoundException;
import com.example.orders.mapper.OrderMapper;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-through cache for single orders.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>{@code @Cacheable} is applied by a Spring AOP proxy, so a call from one method of
 * {@code OrderService} to another would bypass it entirely and quietly hit the database every time.
 * Living in its own bean means {@code OrderService} calls it through the proxy.
 *
 * <h2>Why the cache key is the order id and nothing else</h2>
 *
 * <p>Caching the <em>authorized</em> result would be a data leak: {@code getOrder(caller, id)} returns
 * the same order for its owner and for staff, so a key built from the id alone would let whoever asks
 * second read whatever the first caller was allowed to see. Keying by caller instead would be correct
 * but pointless - every user gets their own copy of identical data, and the hit rate collapses.
 *
 * <p>So the split is: this class caches the order, and the caller's right to see it is checked
 * afterwards, on every request, against the {@code customerId} in the cached response. The cache holds
 * data; it never holds a decision.
 */
@Component
public class OrderCache {

    private static final Logger log = LoggerFactory.getLogger(OrderCache.class);

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final CacheManager cacheManager;

    OrderCache(OrderRepository orderRepository, OrderMapper orderMapper, CacheManager cacheManager) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
        this.cacheManager = cacheManager;
    }

    /**
     * The order, from Redis if present and from PostgreSQL otherwise.
     *
     * <p>Transactional because mapping walks the item collection: the entity graph loads it in the same
     * query, but it still has to be read inside a session. On a cache hit the method body never runs, so
     * a hit costs no transaction and no connection at all.
     *
     * <p>Throws rather than returning null for a missing order, and {@code disableCachingNullValues}
     * means nothing is stored for the failure - so a not-found answer is never pinned for the TTL.
     */
    @Cacheable(cacheNames = CacheConfig.ORDERS, key = "#orderId")
    @Transactional(readOnly = true)
    public OrderResponse findById(Long orderId) {
        log.debug("Cache miss for order {} - loading from the database", orderId);
        return orderRepository.findWithItemsById(orderId)
                .map(orderMapper::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));
    }

    /**
     * Drops an order from the cache once its transaction has committed.
     *
     * <p>The timing is the whole point, and it is why this is not {@code @CacheEvict}. Evicting inside
     * the transaction opens a window: the row is still uncommitted, so a concurrent reader misses the
     * cache, loads the <em>old</em> row, and writes it straight back. The entry is then stale until the
     * TTL - the exact failure eviction was supposed to prevent, made more likely by evicting eagerly.
     *
     * <p>Registering an {@code afterCommit} callback closes it: by the time the entry is removed, the
     * new row is visible to every reader, so the next miss loads the new state.
     *
     * <p>The eviction itself goes through {@link CacheManager} rather than an annotation because this is
     * an internal call - an annotated method invoked from inside this class would not be intercepted.
     */
    public void evictAfterCommit(Long orderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction in progress: nothing to wait for.
            evict(orderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(orderId);
            }
        });
    }

    private void evict(Long orderId) {
        Cache cache = cacheManager.getCache(CacheConfig.ORDERS);
        if (cache == null) {
            // Configured in CacheConfig with createOnMissingCache disabled, so this would mean the
            // cache name and the configuration have drifted apart.
            log.error("Cache '{}' is not configured - order {} cannot be evicted",
                    CacheConfig.ORDERS, orderId);
            return;
        }
        // A failure here is handled by the CacheErrorHandler, which logs it and lets the request
        // succeed. The TTL is the backstop for the stale entry it leaves behind.
        cache.evict(orderId);
        log.debug("Evicted order {} from cache", orderId);
    }
}
