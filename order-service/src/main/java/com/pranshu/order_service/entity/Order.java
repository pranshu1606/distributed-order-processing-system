package com.pranshu.order_service.entity;

import java.util.UUID;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name="orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotNull
    private Long productId;

    @Column(nullable = false)
    @NotNull
    @Min(1)
    private Integer quantity;

    @Column(nullable = false)
    @NotBlank
    private String status;

    // Links the order to its stock reservation in Inventory (null for older orders).
    @Column(unique = true)
    private UUID reservationId;

    // Client-chosen key that makes retries safe: the same key returns the same order.
    @Column(unique = true, length = 100)
    private String idempotencyKey;
}
