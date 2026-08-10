package com.example.orders.cache;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The application keeps working when Redis does not.
 *
 * <p>Redis is pointed at a closed port for this context, which is what an outage actually looks like to
 * the client library. The shared container is left alone - every other test class depends on it.
 *
 * <p>This is the test that justifies both the {@code CacheErrorHandler} and the decision to keep Redis
 * out of the readiness probe. Without the handler, an unreachable cache turns every cached read into a
 * 500 and the system is strictly less available than before the cache was added.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUsers.class)
class CacheDegradationIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        // Registered after Containers, so this wins: nothing is listening on 6399.
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6399);
        registry.add("spring.data.redis.password", () -> "");

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
    HealthEndpoint health;

    @Test
    void servesOrdersFromPostgresWhileRedisIsDown() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        String created = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":2}]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(created).get("id").asLong();

        // Every read misses, fails to reach Redis, is logged by the CacheErrorHandler, and falls
        // through to the database. Slower, still correct.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/api/orders/" + orderId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId))
                    .andExpect(jsonPath("$.status").value("CREATED"));
        }
    }

    @Test
    void keepsAcceptingTrafficEvenThoughItReportsItselfUnhealthy() {
        // The root health endpoint is honest about the outage...
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.healthForPath("redis").getStatus()).isEqualTo(Status.DOWN);

        // ...while readiness stays UP, so an orchestrator does not pull this instance out of the load
        // balancer over a degraded optimisation that has a working fallback.
        assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }
}
