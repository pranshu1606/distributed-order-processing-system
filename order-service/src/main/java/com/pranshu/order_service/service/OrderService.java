package com.pranshu.order_service.service;

import com.pranshu.order_service.dto.InventoryResponse;
import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final RestClient restClient;

    public OrderService(OrderRepository orderRepository, RestClient restClient){
        this.orderRepository = orderRepository;
        this.restClient = restClient;
    }

    public Order createOrder(Order order) {
        Integer availableQuantity =
                getInventoryQuantity(order.getProductId());
        if (availableQuantity < order.getQuantity()) {
            throw new RuntimeException("Not enough inventory");
        }
        reduceInventory(
                order.getProductId(),
                order.getQuantity()
        );
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
}
