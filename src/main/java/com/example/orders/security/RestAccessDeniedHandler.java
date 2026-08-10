package com.example.orders.security;

import java.io.IOException;

import com.example.orders.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers authenticated-but-not-permitted requests with the API's standard error body.
 *
 * <p>The filter-chain counterpart to {@code GlobalExceptionHandler#handleAccessDenied}: this one
 * catches denials decided by URL rules, that one catches denials decided by {@code @PreAuthorize}.
 * Same status, same body, two different places in the request lifecycle.
 */
@Component
class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        RestAuthenticationEntryPoint.writeError(response, objectMapper, ErrorCode.ACCESS_DENIED,
                "You are not allowed to perform this action");
    }
}
