package com.pranshu.order_service.repository;

import java.util.Optional;

import com.pranshu.order_service.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order,Long> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
