package com.example.orders.controller;

import java.util.Optional;

import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.dto.PageResponse;
import com.example.orders.dto.UpdateOrderStatusRequest;
import com.example.orders.entity.OrderStatus;
import com.example.orders.security.AuthenticatedUser;
import com.example.orders.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints.
 *
 * <p>The caller's identity always comes from the validated token, never from the request. There is no
 * {@code customerId} parameter anywhere in this controller by design: the moment one exists, ownership
 * becomes something a client can assert rather than something the server knows.
 *
 * <p>{@code @PreAuthorize} guards what a <em>role</em> may do. Whether a specific caller may touch a
 * specific order depends on who owns it, which is only knowable after loading it - so that check lives
 * in the service layer.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Only customers place orders. Staff acting on a customer's behalf would need its own endpoint. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    OrderResponse createOrder(@AuthenticationPrincipal Jwt jwt,
                              @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(AuthenticatedUser.from(jwt), request);
    }

    /**
     * Lists orders - the caller's own for a customer, all of them for staff.
     *
     * <p>The page size cap is not cosmetic: without one, {@code ?size=1000000} is an unauthenticated
     * denial of service against our own database.
     */
    @GetMapping
    PageResponse<OrderSummaryResponse> listOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listOrders(AuthenticatedUser.from(jwt), Optional.ofNullable(status),
                pageable);
    }

    @GetMapping("/{id}")
    OrderResponse getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.getOrder(AuthenticatedUser.from(jwt), id);
    }

    /**
     * Cancels an order.
     *
     * <p>DELETE, per doc section 5, but this is a state change rather than a deletion - the row stays,
     * with status CANCELLED. Orders are financial records; deleting one destroys the audit trail and
     * whatever accounting depends on it.
     */
    @DeleteMapping("/{id}")
    OrderResponse cancelOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.cancelOrder(AuthenticatedUser.from(jwt), id);
    }

    /**
     * Moves an order through its lifecycle. SUPPORT and ADMIN only.
     *
     * <p>PATCH, not PUT: it changes one field and leaves the rest alone.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    OrderResponse updateStatus(@PathVariable Long id,
                               @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.updateStatus(id, request.status());
    }
}
