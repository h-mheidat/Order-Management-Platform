package com.example.orders.exception;

/** A resource the caller referenced does not exist. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static ResourceNotFoundException order(Long id) {
        return new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order " + id + " was not found");
    }

    public static ResourceNotFoundException user(Long id) {
        return new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User " + id + " was not found");
    }
}
