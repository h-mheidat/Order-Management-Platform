package com.example.orders.exception;

/** The request is well-formed but conflicts with the current state of the system. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static ConflictException emailAlreadyUsed() {
        // Does not echo the address back. The response is visible to whoever sent the request,
        // which on a public registration form is not necessarily the address's owner.
        return new ConflictException(ErrorCode.EMAIL_ALREADY_USED, "Email is already registered");
    }

    public static ConflictException usernameAlreadyUsed() {
        return new ConflictException(ErrorCode.USERNAME_ALREADY_USED, "Username is already taken");
    }
}
