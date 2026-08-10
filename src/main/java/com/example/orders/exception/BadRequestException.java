package com.example.orders.exception;

/** The request is understood but semantically invalid in a way bean validation cannot express. */
public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
