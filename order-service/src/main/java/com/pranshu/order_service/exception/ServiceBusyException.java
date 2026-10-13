package com.pranshu.order_service.exception;

/** The order was not placed because a dependency was too slow or unavailable; retrying is safe. */
public class ServiceBusyException extends RuntimeException {
    public ServiceBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
