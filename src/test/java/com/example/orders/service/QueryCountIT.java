package com.example.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Counts the SQL statements each endpoint issues, so an N+1 cannot be reintroduced unnoticed.
 *
 * <p>This is the only kind of test that catches the problem in doc section 16. An N+1 is not a
 * correctness bug - every response is right - so no assertion on output will ever fail. It shows up
 * exclusively as query count, and only hurts once there is production data volume. Asserting the count
 * turns a silent performance regression into a failing build.
 *
 * <p>The counts are upper bounds rather than exact numbers, because the framework issues a few
 * statements this test has no interest in pinning. What matters is that they do not grow with the number
 * of orders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUsers.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        // The cache would make the second read of an order issue no queries at all, which would make
        // these assertions meaningless. Disabled so every read really touches the database.
        registry.add("spring.cache.type", () -> "none");
        PRODUCT_SERVICE.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // Echoes the id that was asked for. A fixed id would make every product in a
                // multi-item order collapse to one map entry, and the order would be rejected as
                // referencing products the catalog did not return.
                String path = request.getPath() == null ? "/products/1" : request.getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                                {"id":%s,"name":"Product %s","price":10.00,"available":true}"""
                                .formatted(id, id));
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
    SessionFactory sessionFactory;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = sessionFactory.getStatistics();
        statistics.clear();
    }

    private long queriesSoFar() {
        return statistics.getPrepareStatementCount();
    }

    private long createOrder(String token, int distinctProducts) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int product = 1; product <= distinctProducts; product++) {
            items.append(product > 1 ? "," : "")
                    .append("{\"productId\":%d,\"quantity\":1}".formatted(product));
        }
        String body = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[%s]}".formatted(items)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void readsOneOrderAndItsItemsInASingleQuery() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = createOrder(token, 3);

        statistics.clear();
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.items.length()").value(3));

        // One query, not two - @EntityGraph on findWithItemsById joins the items in. Without it this
        // would be 1 + 1: the order, then the collection when the mapper touches it.
        assertThat(queriesSoFar())
                .as("an order and its items must load in one query")
                .isEqualTo(1);
    }

    @Test
    void listsOrdersWithAQueryCountThatDoesNotGrowWithTheNumberOfOrders() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        for (int order = 0; order < 5; order++) {
            createOrder(token, 2);
        }

        statistics.clear();
        mockMvc.perform(get("/api/orders?size=50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        long forFiveOrders = queriesSoFar();

        for (int order = 0; order < 5; order++) {
            createOrder(token, 2);
        }

        statistics.clear();
        mockMvc.perform(get("/api/orders?size=50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        long forTenOrders = queriesSoFar();

        // The count is identical, which is the whole assertion. Two statements - the page and its
        // count - regardless of how many rows come back.
        assertThat(forTenOrders)
                .as("doubling the number of orders must not change the query count; if this fails, "
                        + "something in the list path is loading a lazy association per row")
                .isEqualTo(forFiveOrders);
        assertThat(forTenOrders).isLessThanOrEqualTo(2);
    }

    @Test
    void doesNotQueryTheCustomerRowJustToPutItsIdInAResponse() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = createOrder(token, 1);

        statistics.clear();
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.customerId").exists());

        // Reading only getId() on a lazy @ManyToOne proxy does not initialise it - the foreign key is
        // already there. Touching any other property of the customer would add a query per order.
        assertThat(queriesSoFar()).isEqualTo(1);
    }
}
