package com.example.orders.exception;

/**
 * Base class for every failure this application raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the HTTP status and the machine-readable code are decided
 * where the problem is detected, by the code that understands it - not guessed at later by a handler
 * inspecting exception types.
 *
 * <p>Stack traces are suppressed: these are expected outcomes (a missing order, a duplicate email),
 * not bugs. Filling in a stack trace for each one costs real time on a hot path and adds noise to
 * the logs. Genuine faults keep theirs.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ApiException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
