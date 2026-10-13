package com.pranshu.order_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pranshu.order_service.dto.OrderRequest;
import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService){
        this.orderService = orderService;
    }

    @PostMapping
    public Order createOrder(@Valid @RequestBody OrderRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey){
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-100 characters");
        }
        return orderService.createOrder(request, idempotencyKey);
    }

    @GetMapping("/{orderId}")
    public Order getOrder(@PathVariable Long orderId){
        return orderService.getOrder(orderId);
    }

    @GetMapping
    public List<Order> getAllOrders(){
        return orderService.getAllOrders();
    }
}
