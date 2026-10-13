package com.pranshu.order_service.service;

import com.pranshu.order_service.dto.OrderRequest;
import com.pranshu.order_service.entity.Order;
import com.pranshu.order_service.exception.IdempotencyKeyReusedException;
import com.pranshu.order_service.exception.OrderNotFoundException;
import com.pranshu.order_service.exception.ServiceBusyException;
import com.pranshu.order_service.repository.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    public OrderService(OrderRepository orderRepository, InventoryClient inventoryClient){
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
    }

    public Order getOrder(Long orderId){
        return orderRepository.
                findById(orderId)
                .orElseThrow(()->new OrderNotFoundException("Order Not Found"));
    }

    public Order createOrder(OrderRequest request) {
        return createOrder(request, null);
    }

    public Order createOrder(OrderRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return sameOrderOrReject(existing.get(), request); // a retry: no new stock is reserved
            }
        }

        // Inventory checks and reduces authoritative stock in one transaction.
        // The reservation ID lets us undo that reduction if this order is not saved.
        UUID reservationId = UUID.randomUUID();
        try {
            inventoryClient.reserve(request.getProductId(), request.getQuantity(), reservationId);
        } catch (HttpClientErrorException rejected) {
            throw rejected; // 404/409: Inventory rolled back, nothing is reserved
        } catch (RestClientException unknownOutcome) {
            // Timeout, connection failure or Inventory 5xx: the reduction may still
            // commit later, so release it. Release also blocks a late reduction.
            inventoryClient.releaseLater(reservationId);
            throw new ServiceBusyException("Inventory is busy; please retry", unknownOutcome);
        }

        Order order = new Order();
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        order.setStatus("CREATED");
        order.setReservationId(reservationId);
        order.setIdempotencyKey(idempotencyKey);

        try {
            return orderRepository.save(order);
        } catch (DataIntegrityViolationException duplicate) {
            inventoryClient.releaseLater(reservationId);
            if (idempotencyKey == null) {
                throw duplicate;
            }
            // A concurrent retry with the same key saved first: give back our stock, return theirs.
            return sameOrderOrReject(orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> duplicate), request);
        } catch (RuntimeException saveFailed) {
            inventoryClient.releaseLater(reservationId);
            throw saveFailed;
        }
    }

    private Order sameOrderOrReject(Order existing, OrderRequest request) {
        if (!existing.getProductId().equals(request.getProductId())
                || !existing.getQuantity().equals(request.getQuantity())) {
            throw new IdempotencyKeyReusedException("Idempotency-Key was already used for a different order");
        }
        return existing;
    }

    public List<Order> getAllOrders(){
        return orderRepository.findAll();
    }
}
