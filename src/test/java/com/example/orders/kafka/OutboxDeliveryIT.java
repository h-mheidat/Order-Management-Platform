package com.example.orders.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.orders.entity.OutboxStatus;
import com.example.orders.entity.Role;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.repository.ProcessedEventRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The outbox reaches Kafka, and the consumer handles each event once.
 *
 * <p>Uses the real broker rather than a mocked {@code KafkaTemplate}. Serialization, topic creation,
 * partition assignment, consumer-group behaviour and the idempotency insert are precisely the parts that
 * break in practice, and a mock verifies none of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUsers.class)
class OutboxDeliveryIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        // A dedicated topic and consumer group. Sharing either with another test class would mean one
        // class's events being consumed by the other's listener.
        registry.add("app.kafka.topics.orders", () -> "orders-outbox-it");
        registry.add("spring.kafka.consumer.group-id", () -> "orders-outbox-it-group");
        registry.add("app.outbox.poll-interval", () -> "200");

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
    ProcessedEventRepository processedEvents;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaTopicProperties topics;

    private long createOrder() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        String body = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":1}]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void drainsTheOutboxToKafkaAndTheConsumerRecordsTheEvent() throws Exception {
        long orderId = createOrder();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> outboxRepository.findAll().stream()
                        .filter(row -> row.getAggregateId().equals(String.valueOf(orderId)))
                        .anyMatch(row -> row.getStatus() == OutboxStatus.PUBLISHED));

        var row = outboxRepository.findAll().stream()
                .filter(candidate -> candidate.getAggregateId().equals(String.valueOf(orderId)))
                .findFirst().orElseThrow();

        // markPublished sets both fields together, and ck_outbox_events_published_at refuses a
        // PUBLISHED row with no timestamp.
        assertThat(row.getPublishedAt()).isNotNull();

        // The consumer received it and recorded the event id in the same transaction as its side effect.
        String eventId = row.getEventId().toString();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> processedEvents.existsById(eventId));
    }

    @Test
    void handlesTheSameEventOnceEvenWhenItIsDeliveredTwice() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(eventId, OrderCreatedEvent.TYPE, "Order", "424242",
                OffsetDateTime.now(), objectMapper.readTree("""
                        {"orderId":424242,"customerId":7,"totalPrice":10.00,"itemCount":1}"""));

        // Redelivery of an identical record is normal, not exceptional: Kafka is at-least-once, and the
        // outbox publisher republishes an event whose send succeeded but whose commit did not.
        kafkaTemplate.send(topics.orders(), envelope.aggregateId(), envelope);
        kafkaTemplate.send(topics.orders(), envelope.aggregateId(), envelope);
        kafkaTemplate.flush();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> processedEvents.existsById(eventId.toString()));

        // The second copy is consumed and discarded; give it time to have happened before asserting
        // that it left nothing behind.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> countLedgerRowsFor(eventId) == 1);

        assertThat(countLedgerRowsFor(eventId))
                .as("a redelivered event must not be processed twice; the side effect is bound to "
                        + "this insert by a shared transaction, so one row means one execution")
                .isEqualTo(1);
    }

    @Test
    void sendsAnUnidentifiableEventToTheDeadLetterTopicInsteadOfBlockingThePartition() throws Exception {
        // No eventId, so it cannot be de-duplicated. The listener rejects it, the error handler treats
        // that as non-retryable, and it leaves the partition instead of being retried forever.
        EventEnvelope unidentifiable = new EventEnvelope(null, OrderCreatedEvent.TYPE, "Order", "777",
                OffsetDateTime.now(), objectMapper.readTree("{}"));
        kafkaTemplate.send(topics.orders(), "777", unidentifiable);
        kafkaTemplate.flush();

        // A well-formed event on the same key - therefore the same partition - must still be consumed.
        // That is the proof the poison message did not wedge the partition behind it.
        UUID goodEventId = UUID.randomUUID();
        kafkaTemplate.send(topics.orders(), "777", new EventEnvelope(goodEventId,
                OrderCreatedEvent.TYPE, "Order", "777", OffsetDateTime.now(),
                objectMapper.readTree("""
                        {"orderId":777,"customerId":1,"totalPrice":1.00,"itemCount":1}""")));
        kafkaTemplate.flush();

        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(250))
                .until(() -> processedEvents.existsById(goodEventId.toString()));
    }

    private long countLedgerRowsFor(UUID eventId) {
        return processedEvents.findAll().stream()
                .filter(event -> event.getEventId().equals(eventId.toString()))
                .count();
    }
}
