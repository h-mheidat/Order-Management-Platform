package com.example.orders.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.example.orders.cache.OrderCache;
import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderItemRequest;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.dto.PageResponse;
import com.example.orders.dto.ProductResponse;
import com.example.orders.entity.Order;
import com.example.orders.entity.OrderItem;
import com.example.orders.entity.OrderStatus;
import com.example.orders.entity.OutboxEvent;
import com.example.orders.entity.User;
import com.example.orders.exception.BadRequestException;
import com.example.orders.exception.ConflictException;
import com.example.orders.exception.ErrorCode;
import com.example.orders.exception.ResourceNotFoundException;
import com.example.orders.kafka.OrderCreatedEvent;
import com.example.orders.mapper.OrderMapper;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.repository.UserRepository;
import com.example.orders.security.AuthenticatedUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything a caller can do to an order.
 *
 * <h2>Where the transaction boundary is, and why</h2>
 *
 * <p>{@link #createOrder} is deliberately <b>not</b> annotated {@code @Transactional}. It has to call
 * the external product service, and a network call inside a transaction holds a database connection
 * and its locks open for the duration of that call. When the upstream slows to two seconds, every
 * in-flight order creation is holding a pooled connection for two seconds, the pool empties, and an
 * application that merely reads orders stops working too. A slow dependency becomes a total outage.
 *
 * <p>So the sequence is: fetch prices with no transaction open, then open a short transaction that
 * does nothing but write. The write is expressed with a {@link TransactionTemplate} rather than
 * {@code @Transactional} so the boundary is visible as a block of code - and because a
 * {@code @Transactional} method called from another method of the same class would not be
 * transactional at all, Spring's proxy having been bypassed.
 *
 * <p>Inside that transaction three things commit together: the order, its items, and the outbox row.
 * All or nothing - see {@link OutboxEvent} for why the event cannot simply be sent to Kafka here.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderCache orderCache;
    private final OutboxEventRepository outboxRepository;
    private final UserRepository userRepository;
    private final ProductCatalog productCatalog;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    OrderService(OrderRepository orderRepository, OrderCache orderCache,
                 OutboxEventRepository outboxRepository,
                 UserRepository userRepository, ProductCatalog productCatalog,
                 OrderMapper orderMapper, ObjectMapper objectMapper,
                 TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.orderCache = orderCache;
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.productCatalog = productCatalog;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Creates an order for the calling customer.
     *
     * <p>Steps, in the order doc section 7 lays out: verify the caller, verify the products, compute
     * the price, build the order and its items, save, and record the outbox event.
     */
    public OrderResponse createOrder(AuthenticatedUser caller, CreateOrderRequest request) {
        List<OrderItemRequest> requestedItems = mergeDuplicateProducts(request.items());

        // --- Outside any transaction: the network call. ---
        Map<Long, ProductResponse> products = productCatalog
                .findAllById(requestedItems.stream().map(OrderItemRequest::productId).toList())
                // block() is correct here: this is a servlet request thread that has nothing else to
                // do until the prices arrive. Resilience4j has already bounded how long that can be.
                .block();

        Objects.requireNonNull(products, "product catalog returned no result");
        validateProductsAreOrderable(requestedItems, products);

        // --- The transaction: writes only, no I/O other than the database. ---
        Order saved = transactionTemplate.execute(status -> {
            // getReferenceById, not findById: only the foreign key is needed, so there is no reason
            // to issue a select for the customer row.
            User customer = userRepository.getReferenceById(caller.id());

            Order order = new Order(customer);
            requestedItems.forEach(item -> order.addItem(new OrderItem(
                    item.productId(), item.quantity(), products.get(item.productId()).price())));
            order.setTotalPrice(totalOf(order));

            Order persisted = orderRepository.save(order);
            outboxRepository.save(outboxEventFor(persisted));
            return persisted;
        });

        log.info("Created order id={} customerId={} total={} items={}",
                saved.getId(), caller.id(), saved.getTotalPrice(), saved.getItems().size());
        return orderMapper.toResponse(saved);
    }

    /**
     * One order in full.
     *
     * <p>A customer asking for someone else's order gets 404, not 403. A 403 would confirm the order
     * exists, which is exactly the fact they are not entitled to - enumerate ids and you learn how
     * many orders the system holds and which ranges are live. Staff get the real answer.
     */
    public OrderResponse getOrder(AuthenticatedUser caller, Long orderId) {
        // Not @Transactional: on a cache hit there is nothing to do in a database session, and opening
        // a transaction to then not use it takes a connection out of the pool for no reason. The cache
        // opens its own when it actually has to load.
        OrderResponse order = orderCache.findById(orderId);

        // Authorization happens here, on every request, against the freshly returned data - never
        // inside the cached value. The cache stores the order; it must never store the decision about
        // who may see it, or the second caller inherits the first caller's permissions.
        if (!caller.isStaff() && !caller.owns(order.customerId())) {
            throw ResourceNotFoundException.order(orderId);
        }
        return order;
    }

    /**
     * A page of orders: the caller's own for a customer, everyone's for staff.
     *
     * <p>The scoping is not optional and not a filter the client can change. A customer's query is
     * always constrained to their own id in the SQL, so there is no request they can craft that returns
     * another customer's orders.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(AuthenticatedUser caller,
                                                         Optional<OrderStatus> status,
                                                         Pageable pageable) {
        Page<Order> page = caller.isStaff()
                ? status.map(value -> orderRepository.findAllByStatus(value, pageable))
                        .orElseGet(() -> orderRepository.findAll(pageable))
                : status.map(value -> orderRepository
                                .findAllByCustomerIdAndStatus(caller.id(), value, pageable))
                        .orElseGet(() -> orderRepository.findAllByCustomerId(caller.id(), pageable));

        return PageResponse.from(page.map(orderMapper::toSummary));
    }

    /**
     * Cancels an order.
     *
     * <p>Transactional, and with no external call inside - so {@code @Transactional} is the right tool
     * here where {@link TransactionTemplate} was needed for creation.
     *
     * <p>Reads through {@code findWithItemsById} so the response can include items without a second
     * query after the transaction has closed.
     */
    @Transactional
    public OrderResponse cancelOrder(AuthenticatedUser caller, Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .filter(candidate -> caller.isStaff() || caller.owns(candidate.getCustomer().getId()))
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        if (!order.getStatus().isCancellable()) {
            throw new ConflictException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "An order that is " + order.getStatus() + " can no longer be cancelled");
        }

        order.setStatus(OrderStatus.CANCELLED);
        // No explicit save: the entity is managed, so the change is flushed at commit. Calling save()
        // here would work but suggests it is required, which is how developers start calling it
        // everywhere and stop understanding when writes actually happen.
        orderCache.evictAfterCommit(orderId);
        log.info("Cancelled order id={} by userId={}", orderId, caller.id());
        return orderMapper.toResponse(order);
    }

    /**
     * Moves an order to a new status. SUPPORT and ADMIN only - enforced at the controller.
     *
     * <p>This is the method optimistic locking exists for. Two support agents updating the same order
     * at the same moment both read version 3; the first commit writes version 4, and the second one's
     * {@code update ... where version = 3} matches no row, so it fails rather than silently discarding
     * the first agent's change. The failure surfaces as 409.
     */
    @Transactional
    public OrderResponse updateStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> ResourceNotFoundException.order(orderId));

        OrderStatus current = order.getStatus();
        if (current == newStatus) {
            // Idempotent: asking for the status it already has is not an error, and treating it as one
            // makes a retried request fail.
            return orderMapper.toResponse(order);
        }
        if (!current.canTransitionTo(newStatus)) {
            throw new BadRequestException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION,
                    "An order cannot move from " + current + " to " + newStatus);
        }

        order.setStatus(newStatus);
        orderCache.evictAfterCommit(orderId);
        log.info("Order id={} status {} -> {}", orderId, current, newStatus);
        return orderMapper.toResponse(order);
    }

    /**
     * Sums the order total with a Stream reduce.
     *
     * <p>{@code reduce} rather than a mutable accumulator because {@link BigDecimal} is immutable -
     * {@code add} returns a new value and discards the receiver, so a {@code forEach} that ignores the
     * return value silently computes zero. Starting from {@code BigDecimal.ZERO} also gives the right
     * answer for an empty order instead of throwing.
     */
    private static BigDecimal totalOf(Order order) {
        return order.getItems().stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                // BigDecimal.ZERO has scale 0, so an order whose lines all happen to be whole
                // numbers would total "40" rather than "40.00". Money gets a fixed scale.
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Collapses repeated product ids into a single line with a summed quantity.
     *
     * <p>{@code uq_order_items_order_product} would otherwise reject the order outright. Merging is
     * friendlier than rejecting and matches what the customer meant: two of the same item is a
     * quantity of two, not two line items.
     */
    private static List<OrderItemRequest> mergeDuplicateProducts(List<OrderItemRequest> items) {
        return items.stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItemRequest::productId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.summingInt(OrderItemRequest::quantity)))
                .entrySet().stream()
                .map(entry -> new OrderItemRequest(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Rejects the order if any product is missing or not for sale.
     *
     * <p>Missing ids are impossible in practice - the catalog raises 404 for them first - but the check
     * stays, because relying on a collaborator's error behaviour to guarantee a map key would
     * otherwise fail as a {@code NullPointerException} the day that behaviour changes.
     */
    private static void validateProductsAreOrderable(List<OrderItemRequest> requested,
                                                     Map<Long, ProductResponse> products) {
        List<Long> unavailable = requested.stream()
                .map(OrderItemRequest::productId)
                .filter(id -> {
                    ProductResponse product = products.get(id);
                    return product == null || !product.available();
                })
                .toList();

        if (!unavailable.isEmpty()) {
            throw new BadRequestException(ErrorCode.PRODUCT_NOT_AVAILABLE,
                    "These products cannot be ordered right now: " + unavailable);
        }
    }

    private OutboxEvent outboxEventFor(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.of(order);
        try {
            return new OutboxEvent("Order", String.valueOf(order.getId()),
                    event.eventType(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Serializing our own record cannot fail for data reasons; if it does, the event contract
            // is broken and the order must not commit either.
            throw new IllegalStateException("Unable to serialize " + event.eventType(), e);
        }
    }
}
