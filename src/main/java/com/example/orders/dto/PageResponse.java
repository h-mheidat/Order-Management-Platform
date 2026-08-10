package com.example.orders.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A page of results in a shape this API controls.
 *
 * <p>Spring's {@code PageImpl} is not a stable serialization contract - Boot logs a warning when one
 * is returned directly, because its JSON has changed between versions and exposes internals like
 * {@code pageable} and {@code sort} that clients then depend on. This exposes only what a client
 * needs to page.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }
}
