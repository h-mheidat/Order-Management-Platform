package com.example.orders.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Carries a caller-supplied correlation id through the request and back out again.
 *
 * <p>Distinct from the trace id, and both are needed. A trace id is generated per request by
 * Micrometer and identifies one hop through this system. A correlation id is chosen by the <em>caller</em>
 * and identifies one business interaction, which may span several requests, a retry, and a client-side
 * workflow this service knows nothing about. When a user says "my order failed at 14:32", the correlation
 * id in their client's logs is what finds it here.
 *
 * <p>Ordered highest so the id is in the MDC before anything else logs - including the security filter
 * chain, whose authentication failures are otherwise the one class of log line with no id attached.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * Inbound ids are validated, not trusted.
     *
     * <p>The value goes into log lines, and a header is attacker-controlled: newlines would let a caller
     * forge whole log entries, and unbounded length would let them flood the log. Anything that does not
     * match is replaced rather than rejected - a malformed id is not worth failing a request over.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        // Echoed back so a client can record the id even when it did not send one.
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Removed in a finally block because request threads are pooled: a leftover value would
            // be attributed to whichever unrelated request the thread serves next.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
