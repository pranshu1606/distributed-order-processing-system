package com.pranshu.order_service.exception;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
