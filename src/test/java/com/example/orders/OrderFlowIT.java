package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The order lifecycle end to end: create, read, list, cancel, and staff status changes.
 *
 * <p>Uses its own MockWebServer as the product service so prices are deterministic and an unavailable
 * product can be produced on demand. A dispatcher, rather than a queue, because the number of upstream
 * calls per test depends on how many distinct products the order contains.
 */
@SpringBootTest
@AutoConfigureMockMvc
// @TestComponent beans are excluded from component scanning by design, so the helper is imported
// explicitly rather than being picked up by accident.
@Import(TestUsers.class)
class OrderFlowIT {

    private static final MockWebServer PRODUCT_SERVICE = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Containers.registerTo(registry);
        PRODUCT_SERVICE.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                // Product 30 is deliberately unsellable, so the "not orderable" path is testable.
                if (path.endsWith("/30")) {
                    return json("""
                            {"id":30,"name":"Discontinued","price":9.99,"available":false}""");
                }
                if (path.startsWith("/products/")) {
                    String id = path.substring(path.lastIndexOf('/') + 1);
                    // Price is 10.00 x the id, so expected totals are easy to state in a test.
                    return json("""
                            {"id":%s,"name":"Product %s","price":%s.00,"available":true}"""
                            .formatted(id, id, Long.parseLong(id) * 10));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        PRODUCT_SERVICE.start();
        registry.add("app.product-service.base-url", () -> PRODUCT_SERVICE.url("/").toString());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestUsers testUsers;

    @Autowired
    OutboxEventRepository outboxRepository;

    private String createOrder(String customerToken, String itemsJson) throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":%s}".formatted(itemsJson)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse().getContentAsString();
    }

    private long idOf(String body) throws IOException {
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void createsAnOrderPricedByTheProductServiceNotByTheClient() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        // Quantity 2 of product 1 (10.00) plus 1 of product 2 (20.00) = 40.00.
        String body = createOrder(token, """
                [{"productId":1,"quantity":2},{"productId":2,"quantity":1}]""");

        // Asserted against the raw JSON, not a parsed node: readTree turns a JSON float into a
        // DoubleNode, and asText() on that yields "40.0" - the scale is lost by the test, not by the
        // application. Money is exactly what goes on the wire, so that is what gets checked.
        assertThat(body).contains("\"totalPrice\":40.00");
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("CREATED");
        assertThat(objectMapper.readTree(body).get("items")).hasSize(2);
    }

    @Test
    void ignoresAnyPriceTheClientTriesToSupply() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        // unitPrice is not a component of OrderItemRequest, so it is simply not bound. A
        // client-supplied price would be a client-supplied discount.
        String body = createOrder(token, """
                [{"productId":1,"quantity":1,"unitPrice":0.01}]""");

        assertThat(body).contains("\"totalPrice\":10.00");
    }

    @Test
    void mergesRepeatedProductsIntoOneLineRatherThanRejectingTheOrder() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        String body = createOrder(token, """
                [{"productId":1,"quantity":2},{"productId":1,"quantity":3}]""");

        // One line of quantity 5, not two lines - uq_order_items_order_product would reject two.
        assertThat(objectMapper.readTree(body).get("items")).hasSize(1);
        assertThat(objectMapper.readTree(body).get("items").get(0).get("quantity").asInt())
                .isEqualTo(5);
        assertThat(body).contains("\"totalPrice\":50.00");
    }

    @Test
    void writesAnOutboxEventInTheSameTransactionAsTheOrder() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);
        long outboxRowsBefore = outboxRepository.count();

        String body = createOrder(token, """
                [{"productId":1,"quantity":1}]""");

        assertThat(outboxRepository.count())
                .as("the order and its event must commit together")
                .isEqualTo(outboxRowsBefore + 1);

        String orderId = String.valueOf(idOf(body));
        var event = outboxRepository.findAll().stream()
                .filter(row -> row.getAggregateId().equals(orderId))
                .findFirst()
                .orElseThrow();
        assertThat(event.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains("\"orderId\"");
    }

    @Test
    void rejectsAnOrderForAProductThatIsNotForSale() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":30,"quantity":1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_AVAILABLE"));
    }

    @Test
    void rejectsInvalidQuantitiesAndEmptyOrders() throws Exception {
        String token = testUsers.tokenFor(Role.CUSTOMER);

        // Nested @Valid on the item list is what makes this a 400 instead of a 500 from the check
        // constraint further down.
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":0}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void hidesOtherCustomersOrdersBehindA404RatherThanA403() throws Exception {
        String owner = testUsers.tokenFor(Role.CUSTOMER);
        String stranger = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = idOf(createOrder(owner, """
                [{"productId":1,"quantity":1}]"""));

        // 403 would confirm the order exists, which is precisely the fact a stranger is not entitled
        // to. Enumerating ids would then reveal how many orders the system holds.
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    void scopesTheOrderListToTheCallerForCustomersAndShowsEverythingToStaff() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        String otherCustomer = testUsers.tokenFor(Role.CUSTOMER);
        String support = testUsers.tokenFor(Role.SUPPORT);

        long mine = idOf(createOrder(customer, """
                [{"productId":1,"quantity":1}]"""));
        long theirs = idOf(createOrder(otherCustomer, """
                [{"productId":2,"quantity":1}]"""));

        MvcResult customerView = mockMvc.perform(get("/api/orders?size=100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn();
        String ids = customerView.getResponse().getContentAsString();
        assertThat(ids).contains("\"id\":" + mine);
        assertThat(ids)
                .as("the scoping is applied in SQL, so no request can widen it")
                .doesNotContain("\"id\":" + theirs);

        // Support sees both. A list response carries no items - see OrderSummaryResponse.
        MvcResult staffView = mockMvc.perform(get("/api/orders?size=100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + support))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].items").doesNotExist())
                .andReturn();
        assertThat(staffView.getResponse().getContentAsString()).contains("\"id\":" + theirs);
    }

    @Test
    void letsACustomerCancelTheirOwnOrderButNotOnceItHasShipped() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        String support = testUsers.tokenFor(Role.SUPPORT);
        long orderId = idOf(createOrder(customer, """
                [{"productId":1,"quantity":1}]"""));

        mockMvc.perform(delete("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isOk())
                // Cancelled, not deleted: an order is a financial record and the row stays.
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isOk());

        // A second order, walked to SHIPPED, can no longer be cancelled.
        long shipped = idOf(createOrder(customer, """
                [{"productId":2,"quantity":1}]"""));
        for (String next : List.of("CONFIRMED", "PROCESSING", "SHIPPED")) {
            mockMvc.perform(patch("/api/orders/" + shipped + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + support)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"%s\"}".formatted(next)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(delete("/api/orders/" + shipped)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_CANCELLABLE"));
    }

    @Test
    void refusesAnIllegalStatusTransitionAndAcceptsARepeatOfTheCurrentOne() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        String support = testUsers.tokenFor(Role.SUPPORT);
        long orderId = idOf(createOrder(customer, """
                [{"productId":1,"quantity":1}]"""));

        // CREATED cannot jump straight to DELIVERED.
        mockMvc.perform(patch("/api/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DELIVERED"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ORDER_STATUS_TRANSITION"));

        // Setting the status it already has is idempotent, so a retried request does not fail.
        mockMvc.perform(patch("/api/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CREATED"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void deniesACustomerTheStaffStatusEndpoint() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        long orderId = idOf(createOrder(customer, """
                [{"productId":1,"quantity":1}]"""));

        mockMvc.perform(patch("/api/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void deniesStaffTheAbilityToPlaceOrders() throws Exception {
        String support = testUsers.tokenFor(Role.SUPPORT);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":1}]}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsStatisticsToAdminOnly() throws Exception {
        String customer = testUsers.tokenFor(Role.CUSTOMER);
        String admin = testUsers.tokenFor(Role.ADMIN);
        createOrder(customer, """
                [{"productId":1,"quantity":1}]""");

        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                // Every status is present even at zero, so a chart does not have to guess keys.
                .andExpect(jsonPath("$.ordersByStatus.CREATED").exists())
                .andExpect(jsonPath("$.ordersByStatus.DELIVERED").exists())
                .andExpect(jsonPath("$.totalOrders").exists())
                .andExpect(jsonPath("$.totalRevenue").exists())
                .andExpect(jsonPath("$.averageOrderValue").exists());

        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
                .andExpect(status().isForbidden());
    }
}
