package com.example.orders.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.orders.exception.ErrorCode;

/**
 * The single error shape every failing request returns, whatever went wrong.
 *
 * <p>One contract means a client writes one error path. The alternative - Spring's default body
 * here, a custom body there, an empty 403 from the security filter chain - forces callers to guess.
 *
 * @param timestamp  when the failure happened
 * @param status     the HTTP status, repeated in the body so it survives logging and proxies
 * @param error      the machine-readable {@link ErrorCode}; clients branch on this, never on message
 * @param message    human-readable detail, safe to show a user, never containing internals
 * @param fieldErrors per-field detail, present only for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        ErrorCode error,
        String message,
        List<FieldError> fieldErrors) {

    /** Which field was rejected and why - so a form can highlight the offending input. */
    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode error, String message) {
        return new ErrorResponse(OffsetDateTime.now(), error.status().value(), error, message, null);
    }

    public static ErrorResponse of(ErrorCode error, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(OffsetDateTime.now(), error.status().value(), error, message,
                fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
