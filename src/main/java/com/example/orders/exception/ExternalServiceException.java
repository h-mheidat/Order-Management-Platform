package com.example.orders.exception;

/**
 * An upstream service could not be reached, or answered too slowly, or its circuit breaker is open.
 *
 * <p>Maps to 503 rather than 500: the caller's request was fine and retrying later may well work,
 * which is a materially different instruction than "this is broken, stop".
 */
public class ExternalServiceException extends ApiException {

    public ExternalServiceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
