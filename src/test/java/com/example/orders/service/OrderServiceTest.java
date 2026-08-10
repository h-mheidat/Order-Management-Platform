package com.example.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.orders.cache.OrderCache;
import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.ProductResponse;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import com.example.orders.entity.OrderStatus;
import com.example.orders.entity.OutboxEvent;
import com.example.orders.entity.Role;
import com.example.orders.entity.User;
import com.example.orders.exception.BadRequestException;
import com.example.orders.exception.ConflictException;
import com.example.orders.exception.ErrorCode;
import com.example.orders.exception.ResourceNotFoundException;
import com.example.orders.mapper.OrderMapper;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.repository.UserRepository;
import com.example.orders.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Unit tests for the order service: business rules only, no Spring context and no database.
 *
 * <p>Fast enough to run on every save, which is the point - the integration tests already prove the
 * wiring, so these exist to pin down the decisions. Where an integration test would tell you "the
 * request failed", these say which rule rejected it.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(7L, Role.CUSTOMER);
    private static final AuthenticatedUser OTHER_CUSTOMER = new AuthenticatedUser(8L, Role.CUSTOMER);
    private static final AuthenticatedUser SUPPORT = new AuthenticatedUser(99L, Role.SUPPORT);

    @Mock
    OrderRepository orderRepository;
    @Mock
    OrderCache orderCache;
    @Mock
    OutboxEventRepository outboxRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ProductCatalog productCatalog;
    @Mock
    TransactionTemplate transactionTemplate;

    @Captor
    ArgumentCaptor<Order> savedOrder;
    @Captor
    ArgumentCaptor<OutboxEvent> savedOutboxEvent;

    OrderService orderService;

    private final OrderMapper orderMapper = new OrderMapper();
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderCache, outboxRepository,
                userRepository, productCatalog, orderMapper, objectMapper, transactionTemplate);
    }

    /**
     * Runs the transactional block inline.
     *
     * <p>Stubbed rather than mocked away entirely, because the block is where the order is actually
     * built - skipping it would leave nothing to assert.
     */
    @SuppressWarnings("unchecked")
    private void executeTransactionsInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }

    private static User customerEntity() {
        User user = new User("ahmad", "ahmad@test.com", "{bcrypt}hash", Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", CUSTOMER.id());
        return user;
    }

    private static Order orderWith(OrderStatus status, Long ownerId) {
        User owner = new User("owner", "owner@test.com", "{bcrypt}hash", Role.CUSTOMER);
        ReflectionTestUtils.setField(owner, "id", ownerId);
        Order order = new Order(owner);
        ReflectionTestUtils.setField(order, "id", 100L);
        order.addItem(new OrderItem(10L, 1, new BigDecimal("10.00")));
        order.setStatus(status);
        order.setTotalPrice(new BigDecimal("10.00"));
        return order;
    }

    private static ProductResponse product(long id, String price, boolean available) {
        return new ProductResponse(id, "Product " + id, new BigDecimal(price), available);
    }

    // ----------------------------------------------------------------------------- create

    @Test
    void computesTheTotalFromQuantityTimesPrice() {
        executeTransactionsInline();
        when(productCatalog.findAllById(any())).thenReturn(Mono.just(Map.of(
                10L, product(10L, "25.50", true),
                20L, product(20L, "99.99", true))));
        when(userRepository.getReferenceById(CUSTOMER.id())).thenReturn(customerEntity());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1L);
            return order;
        });

        OrderResponse response = orderService.createOrder(CUSTOMER, new CreateOrderRequest(List.of(
                new OrderItemRequest(10L, 2), new OrderItemRequest(20L, 1))));

        // 2 x 25.50 + 1 x 99.99, at a fixed scale of 2.
        assertThat(response.totalPrice()).isEqualTo(new BigDecimal("150.99"));
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void writesAnOutboxEventForEveryOrderItCreates() {
        executeTransactionsInline();
        when(productCatalog.findAllById(any()))
                .thenReturn(Mono.just(Map.of(10L, product(10L, "10.00", true))));
        when(userRepository.getReferenceById(CUSTOMER.id())).thenReturn(customerEntity());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 55L);
            return order;
        });

        orderService.createOrder(CUSTOMER,
                new CreateOrderRequest(List.of(new OrderItemRequest(10L, 1))));

        verify(outboxRepository).save(savedOutboxEvent.capture());
        OutboxEvent event = savedOutboxEvent.getValue();
        assertThat(event.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo("55");
        // The payload is serialized at creation time and frozen - consumers read this, not the row.
        assertThat(event.getPayload()).contains("\"orderId\":55").contains("\"customerId\":7");
    }

    @Test
    void mergesRepeatedProductsIntoASingleLine() {
        executeTransactionsInline();
        when(productCatalog.findAllById(any()))
                .thenReturn(Mono.just(Map.of(10L, product(10L, "10.00", true))));
        when(userRepository.getReferenceById(CUSTOMER.id())).thenReturn(customerEntity());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 1L);
            return order;
        });

        orderService.createOrder(CUSTOMER, new CreateOrderRequest(List.of(
                new OrderItemRequest(10L, 2), new OrderItemRequest(10L, 3))));

        verify(orderRepository).save(savedOrder.capture());
        // One line of quantity 5. Two lines would violate uq_order_items_order_product.
        assertThat(savedOrder.getValue().getItems()).hasSize(1);
        assertThat(savedOrder.getValue().getItems().iterator().next().getQuantity()).isEqualTo(5);
    }

    @Test
    void refusesToCreateAnOrderForAnUnavailableProductAndWritesNothing() {
        when(productCatalog.findAllById(any()))
                .thenReturn(Mono.just(Map.of(10L, product(10L, "10.00", false))));

        assertThatThrownBy(() -> orderService.createOrder(CUSTOMER,
                new CreateOrderRequest(List.of(new OrderItemRequest(10L, 1)))))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);

        // Validated before the transaction opens, so nothing is written and no connection is taken.
        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(transactionTemplate, never()).execute(any());
    }

    // ----------------------------------------------------------------------------- read

    @Test
    void returnsAnOrderToItsOwner() {
        OrderResponse cached = orderMapper.toResponse(orderWith(OrderStatus.CREATED, CUSTOMER.id()));
        when(orderCache.findById(100L)).thenReturn(cached);

        assertThat(orderService.getOrder(CUSTOMER, 100L).id()).isEqualTo(100L);
    }

    @Test
    void hidesAnotherCustomersOrderBehindNotFound() {
        OrderResponse cached = orderMapper.toResponse(orderWith(OrderStatus.CREATED, CUSTOMER.id()));
        when(orderCache.findById(100L)).thenReturn(cached);

        // 404 rather than 403: a 403 confirms the order exists, which is the fact a stranger is not
        // entitled to.
        assertThatThrownBy(() -> orderService.getOrder(OTHER_CUSTOMER, 100L))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(exception -> ((ResourceNotFoundException) exception).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void letsStaffReadAnyOrder() {
        OrderResponse cached = orderMapper.toResponse(orderWith(OrderStatus.CREATED, CUSTOMER.id()));
        when(orderCache.findById(100L)).thenReturn(cached);

        assertThat(orderService.getOrder(SUPPORT, 100L).id()).isEqualTo(100L);
    }

    // ----------------------------------------------------------------------------- cancel

    @Test
    void cancelsAnOrderTheCustomerOwnsAndEvictsItFromTheCache() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.CREATED, CUSTOMER.id())));

        OrderResponse response = orderService.cancelOrder(CUSTOMER, 100L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderCache).evictAfterCommit(100L);
    }

    @Test
    void refusesToCancelAnOrderThatHasShipped() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.SHIPPED, CUSTOMER.id())));

        assertThatThrownBy(() -> orderService.cancelOrder(CUSTOMER, 100L))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        verify(orderCache, never()).evictAfterCommit(anyLong());
    }

    @Test
    void refusesToCancelAnotherCustomersOrder() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.CREATED, CUSTOMER.id())));

        assertThatThrownBy(() -> orderService.cancelOrder(OTHER_CUSTOMER, 100L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reportsAMissingOrderAsNotFound() {
        when(orderRepository.findWithItemsById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(CUSTOMER, 404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    // ----------------------------------------------------------------------------- status

    @Test
    void movesAnOrderThroughALegalTransition() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.CREATED, CUSTOMER.id())));

        assertThat(orderService.updateStatus(100L, OrderStatus.CONFIRMED).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        verify(orderCache).evictAfterCommit(100L);
    }

    @Test
    void refusesAnIllegalTransition() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.CREATED, CUSTOMER.id())));

        assertThatThrownBy(() -> orderService.updateStatus(100L, OrderStatus.DELIVERED))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
    }

    @Test
    void treatsSettingTheCurrentStatusAsANoOpRatherThanAnError() {
        when(orderRepository.findWithItemsById(100L))
                .thenReturn(Optional.of(orderWith(OrderStatus.CONFIRMED, CUSTOMER.id())));

        assertThat(orderService.updateStatus(100L, OrderStatus.CONFIRMED).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        // Nothing changed, so nothing needs evicting - and a retried request must not fail.
        verify(orderCache, never()).evictAfterCommit(anyLong());
    }
}
