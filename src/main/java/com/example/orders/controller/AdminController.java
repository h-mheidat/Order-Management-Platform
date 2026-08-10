package com.example.orders.controller;

import com.example.orders.dto.ErrorResponse;
import com.example.orders.dto.StatisticsResponse;
import com.example.orders.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only endpoints.
 *
 * <p>The role is checked twice - by the URL rule in {@code SecurityConfig} and by
 * {@code @PreAuthorize} here. Deliberate redundancy: neither check is individually load-bearing, so
 * refactoring the URL patterns cannot quietly expose this, and moving the method cannot either.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "ADMIN only.")
class AdminController {

    private final StatisticsService statisticsService;

    AdminController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @Operation(summary = "Order statistics", description = """
            Counts and revenue per status, aggregated by the database.

            Every status is present even at zero, so a client rendering a chart does not have to guess
            which keys are missing. Cancelled orders are excluded from `totalRevenue` and
            `averageOrderValue` - that is money which will never arrive.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED - not an ADMIN",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    StatisticsResponse statistics() {
        return statisticsService.statistics();
    }
}
