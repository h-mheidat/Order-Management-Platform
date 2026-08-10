package com.example.orders.exception;

import org.springframework.http.HttpStatus;

/**
 * The complete set of machine-readable error codes this API can return.
 *
 * <p>Clients branch on the code, never on the message: messages are for humans and are free to
 * change wording, translation or detail without breaking anyone. Keeping the codes in one enum also
 * means the API's failure surface can be read in one place instead of being scattered across
 * {@code throw} statements.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_ORDER_STATUS_TRANSITION(HttpStatus.BAD_REQUEST),

    /**
     * Deliberately covers both "no such email" and "wrong password". Distinguishing them turns the
     * login endpoint into a way to enumerate which email addresses have accounts.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),

    EMAIL_ALREADY_USED(HttpStatus.CONFLICT),
    USERNAME_ALREADY_USED(HttpStatus.CONFLICT),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT),
    /** Two writers touched the same order at once - see the {@code @Version} column. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT),

    /** An upstream dependency timed out, failed, or its circuit breaker is open. */
    PRODUCT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
