package com.pranshu.inventory_service.exception;

public class ReservationReleasedException extends RuntimeException {
    public ReservationReleasedException(String message) {
        super(message);
    }
}
