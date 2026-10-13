package com.pranshu.order_service.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidationException(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );
        return errors;
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleInventoryProductNotFound(
            HttpClientErrorException.NotFound exception) {

        return "Product not found in inventory";
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleOrderNotFound(
            OrderNotFoundException exception) {

        return exception.getMessage();
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInsufficientInventory(
            InsufficientInventoryException exception) {

        return exception.getMessage();
    }

    @ExceptionHandler(HttpClientErrorException.Conflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInventoryConflict(
            HttpClientErrorException.Conflict exception) {

        return "Not enough inventory";
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public String handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
        return exception.getMessage();
    }

    // Overload or a stalled dependency: fail fast and tell the client when to retry.
    @ExceptionHandler({ServiceBusyException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<String> handleServiceBusy(Exception exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body("Service busy; please retry");
    }
}
