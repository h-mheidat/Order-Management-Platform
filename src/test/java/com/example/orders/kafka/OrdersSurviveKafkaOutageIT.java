package com.example.orders.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import com.example.orders.entity.OutboxStatus;
import com.example.orders.entity.Role;
import com.example.orders.repository.OutboxEventRepository;
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
 * Orders can still be placed while Kafka is unreachable.
 *
 * <p>This is the entire justification for the outbox pattern, written as a test. Sending to Kafka inside
 * the order transaction would make the broker a hard dependency of placing an order - broker down,
 * checkout down. Instead the order and its event commit to PostgreSQL together, the publisher fails
 * quietly, and delivery happens whenever the broker comes back.
 *
 * <p>Kafka is pointed at a closed port, which is what an outage looks like to the client library. The
 * shared container keeps running for the other test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUsers.class)
class OrdersSurviveKafkaOutageIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        // Registered after Containers so this wins. Nothing is listening on 9099.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        registry.add("app.kafka.topics.orders", () -> "orders-outage-it");
        registry.add("spring.kafka.consumer.group-id", () -> "orders-outage-it-group");
        registry.add("app.outbox.poll-interval", () -> "500");
        // Do not spend the default 60s per send waiting for a broker that is not there.
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "1000");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "1000");
        registry.add("spring.kafka.producer.properties.linger.ms", () -> "0");
        // KafkaAdmin would otherwise spend ~40s retrying topic creation against a broker that is not
        // there before the context finishes starting. This test does not need the topics to exist.
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("spring.kafka.admin.properties.request.timeout.ms", () -> "1000");
        registry.add("spring.kafka.admin.properties.default.api.timeout.ms", () -> "1000");

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
    OutboxEventRepository outboxRepository;

    @Autowired
    HealthEndpoint health;

    @Test
    void acceptsOrdersAndRetainsTheirEventsUntilTheBrokerReturns() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        String body = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":3}]}"""))
                // 201 with the broker down. This single assertion is the point of the whole pattern.
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long orderId = objectMapper.readTree(body).get("id").asLong();

        // Readable afterwards, so it genuinely committed rather than being rolled back.
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // Its event is durable and waiting: not lost, and not blocking anything.
        var event = outboxRepository.findAll().stream()
                .filter(row -> row.getAggregateId().equals(String.valueOf(orderId)))
                .findFirst()
                .orElseThrow();
        assertThat(event.getStatus())
                .as("the event must be retained for later delivery, never discarded")
                .isIn(OutboxStatus.PENDING, OutboxStatus.FAILED);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void reportsTheBrokerDownWithoutFailingReadiness() {
        assertThat(health.healthForPath("kafka").getStatus()).isEqualTo(Status.DOWN);
        // Kafka is excluded from readiness precisely because of the test above: order creation works,
        // so there is no reason to take this instance out of the load balancer.
        assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }
}
