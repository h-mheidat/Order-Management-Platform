package com.example.orders.cache;

import java.time.Duration;

import com.example.orders.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis as a cache.
 *
 * <p>Two decisions here are about safety rather than performance.
 *
 * <p><b>Serialization is typed, not polymorphic.</b> The obvious choice,
 * {@code GenericJackson2JsonRedisSerializer}, writes a {@code @class} field into every entry and
 * instantiates whatever class that field names on the way back. That turns any write access to Redis -
 * a misconfigured instance, a shared cluster, a compromised sidecar - into remote code execution
 * through a deserialization gadget chain. A serializer bound to one known type cannot be talked into
 * constructing anything else.
 *
 * <p><b>Cache failures do not fail requests.</b> See {@link #errorHandler()}.
 */
@Configuration
@EnableCaching
// Implements CachingConfigurer, and that is not optional. Spring's cache infrastructure only adopts a
// custom CacheErrorHandler through this interface - a bare @Bean of that type is silently ignored, so
// the graceful degradation below would not exist and an unreachable Redis would return 500s. The
// symptom appears only during the outage the handler was written for.
public class CacheConfig implements CachingConfigurer {

    /** Cache of single orders by id. */
    public static final String ORDERS = "orders";

    /**
     * How long an order may be served from cache.
     *
     * <p>A TTL is mandatory even though every mutation evicts explicitly. Eviction can be missed - a
     * row changed by a migration, a bulk update, another service, or simply a code path someone forgot
     * to wire - and without an expiry that stale entry is served forever.
     */
    private static final Duration ORDER_TTL = Duration.ofMinutes(10);

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                   ObjectMapper objectMapper) {
        RedisCacheConfiguration orders = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ORDER_TTL)
                // Keys stay human-readable ("orders::42") so a cache can be inspected with redis-cli
                // during an incident instead of being an opaque blob.
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper,
                                OrderResponse.class)))
                // Do not cache nulls. Caching "this order does not exist" would let a burst of
                // requests for a not-yet-visible order pin a negative answer for the whole TTL.
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                // cacheDefaults, and withCacheConfiguration AFTER initialCacheNames - both matter.
                // initialCacheNames re-registers every name it is given with the current
                // cacheDefaults, so a per-cache configuration applied before it is quietly
                // overwritten. Get that order wrong and this cache falls back to the default JDK
                // serializer: it still works, and it reintroduces exactly the deserialization
                // exposure the typed serializer above was chosen to avoid.
                .cacheDefaults(orders)
                // Only caches declared here exist. A typo in a @Cacheable name then fails fast
                // instead of silently creating a cache nobody configured a TTL for.
                .initialCacheNames(java.util.Set.of(ORDERS))
                .withCacheConfiguration(ORDERS, orders)
                .disableCreateOnMissingCache()
                .build();
    }

    /**
     * Makes Redis optional at runtime.
     *
     * <p>Without this, Spring's cache abstraction propagates connection errors, and an unreachable
     * Redis turns every cached read into a 500 - the cache becomes a hard dependency and the system is
     * strictly less available than it was before the cache was added. That is the opposite of the
     * point.
     *
     * <p>With it, a cache failure is logged and the call proceeds to PostgreSQL: slower, still correct.
     * This is the same reasoning that keeps Redis out of the readiness probe.
     *
     * <p>Put errors are swallowed too. A failed write only means the next read misses. A failed
     * <em>evict</em> is the one worth watching, because it can leave a stale entry behind - which is
     * why it is logged at warn, and why {@link #ORDER_TTL} exists as the backstop.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            private final Logger log = LoggerFactory.getLogger("com.example.orders.cache");

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache read failed for {}::{} - falling through to the database: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                                            Object value) {
                log.warn("Cache write failed for {}::{} - the next read will miss: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // The dangerous one: a stale entry may survive until its TTL.
                log.warn("Cache evict FAILED for {}::{} - stale data may be served until the TTL "
                        + "expires: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache clear failed for {}: {}", cache.getName(), exception.getMessage());
            }
        };
    }
}
