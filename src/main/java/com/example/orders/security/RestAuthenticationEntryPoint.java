package com.example.orders.security;

import java.io.IOException;

import com.example.orders.dto.ErrorResponse;
import com.example.orders.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers unauthenticated requests with the API's standard error body.
 *
 * <p>This exists because authentication fails inside the security filter chain, before the
 * DispatcherServlet runs - so {@code GlobalExceptionHandler} never sees it. Without this class a
 * missing or expired token would return an empty 401, and clients would have one response shape they
 * cannot parse like all the others.
 */
@Component
class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // The exception message distinguishes expired from malformed from absent. That is useful in
        // a log and unhelpful to a client, so it is logged by the filter chain and not returned.
        writeError(response, objectMapper, ErrorCode.UNAUTHENTICATED,
                "Authentication is required to access this resource");
    }

    static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                           ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, message));
    }
}
