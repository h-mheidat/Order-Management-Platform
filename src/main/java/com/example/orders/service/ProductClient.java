package com.example.orders.service;

import java.time.Duration;

import com.example.orders.dto.ProductResponse;
import com.example.orders.exception.ErrorCode;
import com.example.orders.exception.ExternalServiceException;
import com.example.orders.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Reads products from the external Product Service.
 *
 * <p>Four protections, layered - and the order matters, because Resilience4j applies its aspects
 * outermost-first as Retry, then CircuitBreaker, then TimeLimiter:
 *
 * <ol>
 *   <li><b>Timeout</b> (innermost) - a slow call is turned into a failed call. Without this the other
 *       three never trigger, because a request that never returns is never a failure.
 *   <li><b>Circuit breaker</b> - once the failure rate crosses the threshold the breaker opens and
 *       calls fail immediately. This is the part that protects the <em>upstream</em>: hammering a
 *       service that is already struggling is how a slow dependency becomes a dead one.
 *   <li><b>Retry</b> (outermost) - covers a single dropped packet or one unlucky request. Retrying
 *       outside the breaker means the breaker still counts each attempt, so retries cannot mask a
 *       genuinely failing dependency.
 *   <li><b>Fallback</b> - what the caller gets when all of the above have been exhausted.
 * </ol>
 *
 * <p>Retries are only safe here because this is a GET: idempotent, so a retry cannot create a
 * duplicate. Retrying a non-idempotent call is how one click becomes two orders.
 *
 * <p>Only a single-product fetch lives here. Fetching many is {@code ProductCatalog}'s job, in its own
 * bean - because Resilience4j works through a Spring AOP proxy, and a call from one method of this
 * class to another would bypass that proxy entirely. The annotations would still be present, still
 * look correct in review, and do absolutely nothing.
 */
@Service
public class ProductClient {

    private static final Logger log = LoggerFactory.getLogger(ProductClient.class);

    /** Named so the configuration in application.yml and the metrics both refer to one instance. */
    private static final String INSTANCE = "productService";

    /**
     * How long to wait for the whole call, retries excluded.
     *
     * <p>Applied as a Reactor operator rather than with {@code @TimeLimiter}: the annotation wraps a
     * {@code CompletableFuture} and would need this method to return one, whereas this way the
     * timeout composes with the rest of the reactive chain and the fallback stays on the same type.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    ProductClient(WebClient productWebClient) {
        this.webClient = productWebClient;
    }

    /**
     * Fetches one product.
     *
     * <p>A 404 is a legitimate answer, not a failure: it means the caller referenced a product that
     * does not exist. It is translated into a 404 for our own caller and must never count towards the
     * circuit breaker - otherwise a client requesting bad ids in a loop could open the breaker and
     * deny service to everyone else.
     */
    // The fallback belongs on @Retry, the OUTERMOST aspect - not on @CircuitBreaker.
    //
    // Resilience4j nests its aspects as Retry( CircuitBreaker( TimeLimiter( call ))). A fallback
    // declared on the circuit breaker therefore runs *inside* the retry: the first 500 is caught by
    // the breaker's fallback and converted into ExternalServiceException, which is not a retryable
    // exception, so Retry sees a failure it must not retry and gives up after one attempt. The
    // annotations look right, the config looks right, and retry never happens.
    //
    // Putting it here means failures propagate outward - counted by the breaker, retried by Retry -
    // and the fallback only runs once the retries are genuinely exhausted.
    @Retry(name = INSTANCE, fallbackMethod = "productFallback")
    @CircuitBreaker(name = INSTANCE)
    public Mono<ProductResponse> getProduct(Long productId) {
        return webClient.get()
                .uri("/products/{id}", productId)
                .retrieve()
                .bodyToMono(ProductResponse.class)
                .timeout(CALL_TIMEOUT)
                .onErrorMap(this::isNotFound,
                        e -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND,
                                "Product " + productId + " was not found"));
    }

    /**
     * Invoked when the retries are used up, or the breaker is open.
     *
     * <p>Signature note: the trailing {@link Throwable} is how Resilience4j matches a fallback, and
     * the return type must match the protected method exactly.
     *
     * <p>This fallback fails rather than substituting a placeholder price. That is the deliberate
     * choice for this call: an order priced from a guess is worse than an order the customer is asked
     * to place again. A fallback that invents data is only appropriate where the data is advisory -
     * a recommendation list, a "customers also bought" panel - never where it becomes money in a
     * database.
     */
    @SuppressWarnings("unused") // Resolved by name by Resilience4j, not called directly.
    private Mono<ProductResponse> productFallback(Long productId, Throwable throwable) {
        // A genuine 404 is passed straight through: it is the upstream's correct answer, not an
        // outage, and turning it into a 503 would tell the caller to retry something that will
        // never succeed.
        if (throwable instanceof ResourceNotFoundException notFound) {
            return Mono.error(notFound);
        }
        log.warn("Product service unavailable for productId={}: {}", productId,
                throwable.toString());
        return Mono.error(new ExternalServiceException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE,
                "Product service is temporarily unavailable. Please try again shortly"));
    }

    private boolean isNotFound(Throwable throwable) {
        return throwable instanceof WebClientResponseException error
                && error.getStatusCode() == HttpStatus.NOT_FOUND;
    }
}
