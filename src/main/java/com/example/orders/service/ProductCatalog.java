package com.example.orders.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.example.orders.dto.ProductResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fetches several products at once.
 *
 * <p>A separate bean from {@link ProductClient}, and that is the whole point. Resilience4j's
 * annotations are applied by a Spring AOP proxy, and a proxy only intercepts calls that arrive from
 * outside the object. Had this fan-out lived in {@code ProductClient} and called {@code this.getProduct},
 * every call would have gone straight to the target method: no timeout, no retry, no circuit breaker,
 * no fallback - with the annotations still sitting there looking correct. Injecting the client means
 * the calls go through the proxy.
 */
@Service
public class ProductCatalog {

    /**
     * Bounds fan-out. An order with 100 line items must not open 100 concurrent sockets to one
     * upstream - unbounded {@code flatMap} is a self-inflicted denial of service on a dependency.
     */
    private static final int MAX_CONCURRENT_LOOKUPS = 8;

    private final ProductClient productClient;

    ProductCatalog(ProductClient productClient) {
        this.productClient = productClient;
    }

    /**
     * Fetches every requested product concurrently, keyed by id.
     *
     * <p>{@code flatMap}, not {@code map}: each lookup is itself asynchronous, so {@code map} would
     * produce a stream of publishers nobody subscribed to. flatMap subscribes to each and merges
     * results as they arrive, so ten lookups cost roughly one round trip rather than ten.
     *
     * <p>Ids are de-duplicated first. Two line items for the same product would otherwise fetch it
     * twice, and {@code collectMap} would silently keep one of the two answers.
     *
     * <p>Fails if any single product fails - {@code flatMap} propagates the first error. That is the
     * right behaviour for order creation: a partial price list would produce an order that is wrong
     * in a way nobody notices until the invoice.
     */
    public Mono<Map<Long, ProductResponse>> findAllById(Collection<Long> productIds) {
        Set<Long> distinct = Set.copyOf(productIds);
        if (distinct.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Flux.fromIterable(distinct)
                .flatMap(productClient::getProduct, MAX_CONCURRENT_LOOKUPS)
                .collectMap(ProductResponse::id, Function.identity());
    }
}
