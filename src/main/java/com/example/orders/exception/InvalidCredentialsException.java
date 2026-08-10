package com.example.orders.exception;

/**
 * Login failed.
 *
 * <p>One exception for "unknown email" and "wrong password" on purpose - see
 * {@link ErrorCode#INVALID_CREDENTIALS}.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }
}
