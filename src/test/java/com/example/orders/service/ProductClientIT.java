package com.example.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.example.orders.dto.ProductResponse;
import com.example.orders.exception.ExternalServiceException;
import com.example.orders.exception.ResourceNotFoundException;
import com.example.orders.support.Containers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that the resilience layer around the product client actually engages.
 *
 * <p>Runs against the real application context rather than a hand-built {@code new ProductClient(...)}.
 * That matters: the timeout, retry, circuit breaker and fallback are applied by a Spring AOP proxy, so
 * a directly constructed instance exercises none of them - the test would pass while proving nothing.
 * A resilience library that is silently inert is precisely the bug worth a test.
 *
 * <p>MockWebServer rather than the WireMock container: the interesting cases are an upstream that hangs
 * and one that fails repeatedly, which need producing on demand.
 *
 * <p>The circuit breaker window is narrowed here so a handful of calls can fill it. The production
 * values in application.yml are deliberately less twitchy.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.productService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.productService.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.productService.wait-duration-in-open-state=60s",
        "resilience4j.circuitbreaker.instances.productService.automatic-transition-from-open-to-half-open-enabled=false",
        "resilience4j.retry.instances.productService.wait-duration=10ms",
        "resilience4j.retry.instances.productService.enable-exponential-backoff=false",
        "resilience4j.retry.instances.productService.enable-randomized-wait=false",
})
class ProductClientIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        PRODUCT_SERVICE.start();
        registry.add("app.product-service.base-url", () -> PRODUCT_SERVICE.url("/").toString());
        registry.add("app.product-service.connect-timeout", () -> "500ms");
        registry.add("app.product-service.response-timeout", () -> "400ms");
    }

    @Autowired
    ProductClient productClient;

    @Autowired
    ProductCatalog productCatalog;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private int requestBaseline;

    @BeforeEach
    void reset() {
        // A fresh dispatcher discards responses a previous test queued but never consumed -
        // otherwise one test's leftovers become the next test's answers.
        PRODUCT_SERVICE.setDispatcher(new QueueDispatcher());
        // Request count is cumulative for the server's lifetime, so assertions compare deltas.
        requestBaseline = PRODUCT_SERVICE.getRequestCount();
        // The breaker is a singleton in the shared context: a test that opens it would fail the next.
        circuitBreakerRegistry.circuitBreaker("productService").reset();
    }

    private int requestsMade() {
        return PRODUCT_SERVICE.getRequestCount() - requestBaseline;
    }

    private static void enqueueProduct(long id, String price) {
        PRODUCT_SERVICE.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":%d,"name":"Product %d","price":%s,"available":true}"""
                        .formatted(id, id, price)));
    }

    private static void enqueueStatus(int status) {
        PRODUCT_SERVICE.enqueue(new MockResponse().setResponseCode(status));
    }

    @Test
    void fetchesAProduct() {
        enqueueProduct(10L, "25.50");

        ProductResponse product = productClient.getProduct(10L).block();

        assertThat(product).isNotNull();
        assertThat(product.id()).isEqualTo(10L);
        assertThat(product.price()).isEqualTo(new BigDecimal("25.50"));
        assertThat(product.available()).isTrue();
    }

    @Test
    void ignoresFieldsItDoesNotKnowAbout() {
        // The upstream must stay free to add fields without breaking this consumer.
        PRODUCT_SERVICE.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":10,"name":"Keyboard","price":25.50,"available":true,
                         "warehouseCode":"AMM-1","introducedIn":2026}"""));

        assertThat(productClient.getProduct(10L).block()).isNotNull();
    }

    @Test
    void retriesAServerErrorAndSucceedsOnALaterAttempt() {
        enqueueStatus(500);
        enqueueStatus(500);
        enqueueProduct(10L, "25.50");

        assertThat(productClient.getProduct(10L).block()).isNotNull();
        // Three attempts for one logical call - the retry is wired, not merely configured.
        assertThat(requestsMade()).isEqualTo(3);
    }

    @Test
    void doesNotRetryAMissingProductAndReportsItAs404() {
        enqueueStatus(404);

        assertThatThrownBy(() -> productClient.getProduct(404L).block())
                .isInstanceOf(ResourceNotFoundException.class);

        // Exactly one attempt: a 404 will not become a 200, so retrying only triples the load.
        assertThat(requestsMade()).isEqualTo(1);
    }

    @Test
    void turnsAHangingUpstreamIntoAFailureRatherThanWaitingForIt() {
        for (int i = 0; i < 3; i++) {
            PRODUCT_SERVICE.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
                    .setBodyDelay(30, TimeUnit.SECONDS));
        }

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> productClient.getProduct(99L).block())
                .isInstanceOf(ExternalServiceException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // Three attempts of ~400ms plus backoff - nowhere near the 30s the upstream wanted. Without
        // a timeout this assertion is what would hang the build.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void failsWithServiceUnavailableRatherThanInventingAPrice() {
        enqueueStatus(500);
        enqueueStatus(500);
        enqueueStatus(500);

        // The fallback deliberately does not substitute a placeholder price. An order priced from a
        // guess is worse than an order the customer is asked to place again.
        assertThatThrownBy(() -> productClient.getProduct(10L).block())
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void opensTheBreakerAfterRepeatedFailuresAndThenStopsCallingUpstream() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("productService");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Four logical calls, each retried three times, fills the four-call window.
        for (int i = 0; i < 12; i++) {
            enqueueStatus(500);
        }
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> productClient.getProduct(10L).block())
                    .isInstanceOf(ExternalServiceException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = requestsMade();
        assertThatThrownBy(() -> productClient.getProduct(10L).block())
                .isInstanceOf(ExternalServiceException.class);

        // The whole purpose of the breaker: while open, the upstream is not contacted at all. This
        // is what stops a struggling dependency being hammered into a dead one.
        assertThat(requestsMade())
                .as("an open breaker must send nothing upstream")
                .isEqualTo(before);
    }

    @Test
    void doesNotOpenTheBreakerOnMissingProducts() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("productService");
        for (int i = 0; i < 8; i++) {
            enqueueStatus(404);
        }

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> productClient.getProduct(404L).block())
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        // Otherwise a client looping over bad ids could deny service to everyone else.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void fetchesManyProductsConcurrentlyAndKeysThemById() {
        enqueueProduct(10L, "25.50");
        enqueueProduct(20L, "99.99");

        var products = productCatalog.findAllById(List.of(10L, 20L)).block();

        assertThat(products).isNotNull().hasSize(2);
        assertThat(products.keySet()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void deDuplicatesRepeatedProductIds() {
        enqueueProduct(10L, "25.50");

        var products = productCatalog.findAllById(List.of(10L, 10L, 10L)).block();

        assertThat(products).isNotNull().hasSize(1);
        // One HTTP call for three references to the same product.
        assertThat(requestsMade()).isEqualTo(1);
    }

    @Test
    void makesNoCallAtAllForAnEmptyRequest() {
        assertThat(productCatalog.findAllById(List.of()).block()).isEmpty();
        assertThat(requestsMade()).isZero();
    }
}
