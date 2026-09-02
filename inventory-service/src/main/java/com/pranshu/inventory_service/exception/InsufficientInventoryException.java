package com.pranshu.inventory_service.exception;

public class InsufficientInventoryException extends RuntimeException{
    public InsufficientInventoryException(String message){
        super(message);
    }
}
