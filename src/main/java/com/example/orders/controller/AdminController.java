package com.example.orders.controller;

import com.example.orders.dto.StatisticsResponse;
import com.example.orders.service.StatisticsService;
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
class AdminController {

    private final StatisticsService statisticsService;

    AdminController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    StatisticsResponse statistics() {
        return statisticsService.statistics();
    }
}
