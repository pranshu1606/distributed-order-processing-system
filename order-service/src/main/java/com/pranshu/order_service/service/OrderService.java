package com.pranshu.order_service.service;

import com.pranshu.order_service.dto.InventoryResponse;
import com.pranshu.order_service.dto.OrderRequest;
import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.exception.InsufficientInventoryException;
import com.pranshu.order_service.exception.OrderNotFoundException;
import com.pranshu.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final RestClient restClient;

    public OrderService(OrderRepository orderRepository, RestClient restClient){
        this.orderRepository = orderRepository;
        this.restClient = restClient;
    }

    public Order getOrder(Long orderId){
        return orderRepository.
                findById(orderId)
                .orElseThrow(()->new OrderNotFoundException("Order Not Found"));
    }

    public Order createOrder(OrderRequest request) {
        Integer availableQuantity =
                getInventoryQuantity(request.getProductId());
        if (availableQuantity < request.getQuantity()) {
            throw new InsufficientInventoryException("Not enough inventory");
        }
        reduceInventory(
                request.getProductId(),
                request.getQuantity()
        );

        Order order = new Order();
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        order.setStatus("CREATED");

        return orderRepository.save(order);
    }

    public Integer getInventoryQuantity(Long productId){
        InventoryResponse response = restClient.get()
                .uri("/inventory/" + productId)
                .retrieve()
                .body(InventoryResponse.class);

        return response.getQuantity();
    }

    private void reduceInventory(Long productId, Integer quantity) {
        restClient.put()
                .uri(uriBuilder -> uriBuilder
                        .path("/inventory/{productId}/reduce")
                        .queryParam("quantity", quantity)
                        .build(productId))
                .retrieve()
                .toBodilessEntity();
    }

    public List<Order> getAllOrders(){
        return orderRepository.findAll();
    }
}
