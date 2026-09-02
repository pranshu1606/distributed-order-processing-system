package com.pranshu.order_service.controller;

import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    private OrderController(OrderService orderService){
        this.orderService = orderService;
    }

    @PostMapping
    public Order createOrder(@Valid @RequestBody Order order){
        return orderService.createOrder(order);
    }
}
