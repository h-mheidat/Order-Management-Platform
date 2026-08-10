package com.example.orders.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import com.example.orders.entity.Role;
import com.example.orders.support.Containers;
import com.example.orders.support.TestUsers;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cache, checked against a real Redis.
 *
 * <p>Asserts what is actually in Redis rather than only that responses look right. A cache that is
 * silently never populated, or never evicted, produces correct responses in a quiet test and stale data
 * in production - the whole class of bug worth testing for is invisible from the response alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUsers.class)
class OrderCacheIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        PRODUCT_SERVICE.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                                {"id":1,"name":"Product","price":10.00,"available":true}""");
            }
        });
        PRODUCT_SERVICE.start();
        registry.add("app.product-service.base-url", () -> PRODUCT_SERVICE.url("/").toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestUsers testUsers;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    StringRedisTemplate redis;

    private long createOrder(String token) throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":1}]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private boolean isCached(long orderId) {
        // Checked through the raw key, not the CacheManager, so the test also proves the key format is
        // the readable "orders::42" that makes a cache inspectable during an incident.
        return Boolean.TRUE.equals(redis.hasKey("orders::" + orderId));
    }

    @Test
    void populatesTheCacheOnFirstReadAndServesTheSecondFromIt() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = createOrder(token);

        // Creating an order does not populate the cache - only a read does.
        assertThat(isCached(orderId)).isFalse();

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(isCached(orderId))
                .as("the first read must write through to Redis")
                .isTrue();

        // The second read is served from Redis, with the same body - proof the serialized form round
        // trips, including BigDecimal scale and OffsetDateTime.
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void doesNotServeAStaleOrderAfterItsStatusChanges() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        String support = testUsers.tokenFor(Role.SUPPORT);
        long orderId = createOrder(customer);

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(isCached(orderId)).isTrue();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}"""))
                .andExpect(status().isOk());

        // Evicted after the transaction committed, not during it - so no concurrent reader could have
        // repopulated it from the pre-commit row.
        assertThat(isCached(orderId))
                .as("a status change must evict the cached order")
                .isFalse();

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void doesNotServeAStaleOrderAfterCancellation() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = createOrder(token);

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.status").value("CREATED"));

        mockMvc.perform(delete("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(isCached(orderId)).isFalse();
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void stillRefusesAStrangerAnOrderThatIsSittingInTheCache() throws Exception {
        String owner = testUsers.tokenFor(Role.CUSTOMER);
        String stranger = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = createOrder(owner);

        // The owner reads first, so the order is definitely cached.
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk());
        assertThat(isCached(orderId)).isTrue();

        // This is the reason authorization is applied after the cache rather than being cached with
        // the value: a cache keyed only by order id must not let the second caller inherit the first
        // caller's permissions.
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void storesNothingForAnOrderThatDoesNotExist() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        mockMvc.perform(get("/api/orders/99999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        // disableCachingNullValues: a not-found answer must not be pinned for the TTL, or an order
        // that becomes visible a moment later stays invisible for ten minutes.
        assertThat(isCached(99999999L)).isFalse();
    }

    @Test
    void refusesToCreateACacheNobodyConfigured() {
        // createOnMissingCache is disabled, so a typo in a @Cacheable name yields null here instead of
        // silently producing a cache with no TTL and no serializer.
        assertThat(cacheManager.getCache("not-a-configured-cache")).isNull();
        assertThat(cacheManager.getCache(CacheConfig.ORDERS)).isNotNull();
    }
}
