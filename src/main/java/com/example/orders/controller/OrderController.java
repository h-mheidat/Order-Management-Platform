package com.example.orders.controller;

import java.util.Optional;

import com.example.orders.dto.CreateOrderRequest;
import com.example.orders.dto.ErrorResponse;
import com.example.orders.dto.OrderResponse;
import com.example.orders.dto.OrderSummaryResponse;
import com.example.orders.dto.PageResponse;
import com.example.orders.dto.UpdateOrderStatusRequest;
import com.example.orders.entity.OrderStatus;
import com.example.orders.security.AuthenticatedUser;
import com.example.orders.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "Orders", description = """
        The order lifecycle. All endpoints require a bearer token.

        Requesting an order that belongs to someone else returns **404, not 403** - a 403 would confirm
        the order exists, which is exactly the fact the caller is not entitled to.""")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing, expired or tampered token",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but the role is not permitted",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Only customers place orders. Staff acting on a customer's behalf would need its own endpoint. */
    @Operation(summary = "Place an order", description = """
            CUSTOMER only. The caller is taken from the token, so an order cannot be placed on another
            account.

            Prices come from the product service, not from the request - a `unitPrice` in the body is
            ignored. Repeated `productId` values are merged into one line with a summed quantity.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or PRODUCT_NOT_AVAILABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRODUCT_SERVICE_UNAVAILABLE - the upstream "
                    + "could not be reached. The order was not created; retry.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    OrderResponse createOrder(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                              @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(AuthenticatedUser.from(jwt), request);
    }

    /**
     * Lists orders - the caller's own for a customer, all of them for staff.
     *
     * <p>The page size cap is not cosmetic: without one, {@code ?size=1000000} is an unauthenticated
     * denial of service against our own database.
     */
    @Operation(summary = "List orders", description = """
            A CUSTOMER sees only their own orders; SUPPORT and ADMIN see all of them. The scoping is
            applied in SQL and cannot be widened by any request.

            Line items are **not** included - use `GET /api/orders/{id}` for those. Returning items in a
            list would mean either one query per order or in-memory pagination.""")
    @GetMapping
    PageResponse<OrderSummaryResponse> listOrders(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Optional status filter") @RequestParam(required = false)
            OrderStatus status,
            // @ParameterObject expands Pageable into page/size/sort query parameters. Without it
            // springdoc documents it as a single opaque object body nobody can call.
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listOrders(AuthenticatedUser.from(jwt), Optional.ofNullable(status),
                pageable);
    }

    @Operation(summary = "Get one order in full",
            description = "Owner or staff only. Served from Redis when warm; authorization is still "
                    + "checked on every request, never cached with the data.")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND - it does not exist, "
            + "or it is not yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))))
    @GetMapping("/{id}")
    OrderResponse getOrder(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                           @PathVariable Long id) {
        return orderService.getOrder(AuthenticatedUser.from(jwt), id);
    }

    /**
     * Cancels an order.
     *
     * <p>DELETE, per doc section 5, but this is a state change rather than a deletion - the row stays,
     * with status CANCELLED. Orders are financial records; deleting one destroys the audit trail and
     * whatever accounting depends on it.
     */
    @Operation(summary = "Cancel an order", description = """
            Sets the status to CANCELLED. The order is **not** deleted - it is a financial record and the
            row remains.

            Only possible while the order is CREATED, CONFIRMED or PROCESSING. Once SHIPPED it is a
            refund problem, not a cancellation.""")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELLABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @DeleteMapping("/{id}")
    OrderResponse cancelOrder(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                              @PathVariable Long id) {
        return orderService.cancelOrder(AuthenticatedUser.from(jwt), id);
    }

    /**
     * Moves an order through its lifecycle. SUPPORT and ADMIN only.
     *
     * <p>PATCH, not PUT: it changes one field and leaves the rest alone.
     */
    @Operation(summary = "Move an order to a new status", description = """
            SUPPORT and ADMIN only.

            Legal transitions: CREATED -> CONFIRMED | CANCELLED; CONFIRMED -> PROCESSING | CANCELLED;
            PROCESSING -> SHIPPED | CANCELLED; SHIPPED -> DELIVERED. DELIVERED and CANCELLED are
            terminal.

            Setting the status it already has is a no-op returning 200, so a retried request does not
            fail.""")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "INVALID_ORDER_STATUS_TRANSITION",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION - another writer "
                    + "changed this order first. Re-read it and retry.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    OrderResponse updateStatus(@PathVariable Long id,
                               @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.updateStatus(id, request.status());
    }
}
