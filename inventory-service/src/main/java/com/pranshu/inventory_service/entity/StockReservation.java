package com.pranshu.inventory_service.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per order attempt that touched stock. Recording the reservation in the
 * same transaction as the stock reduction makes reduce and release idempotent.
 * A RELEASED row with no product is a tombstone: release arrived before (or
 * instead of) the reduction, so a late reduction with that ID is refused.
 */
@Entity
@Table(name = "stock_reservations")
public class StockReservation {
    @Id
    private UUID reservationId;

    private Long productId;

    private Integer quantity;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    protected StockReservation() {
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
