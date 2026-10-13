package com.pranshu.inventory_service.repository;

import com.pranshu.inventory_service.entity.Inventory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryStockRepository {
    private final JdbcTemplate jdbc;

    public InventoryStockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Inventory> reduceIfAvailable(Long productId, Integer quantity) {
        // PostgreSQL locks the row and rechecks the predicate after competing updates.
        // RETURNING gives the updated stock without a second database round trip.
        var rows = jdbc.query("""
                UPDATE inventory SET quantity = quantity - ?
                WHERE product_id = ? AND quantity >= ?
                RETURNING id, product_id, product_name, quantity
                """, (rs, rowNumber) -> new Inventory(
                rs.getLong("id"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getInt("quantity")),
                quantity, productId, quantity);
        return rows.stream().findFirst();
    }

    /**
     * Inserts the reservation unless its ID already exists. A concurrent insert of
     * the same ID waits for the other transaction, so only one of them can win.
     */
    public boolean insertReservation(UUID reservationId, Long productId, Integer quantity, String status) {
        return jdbc.update("""
                INSERT INTO stock_reservations (reservation_id, product_id, quantity, status, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (reservation_id) DO NOTHING
                """, reservationId, productId, quantity, status) == 1;
    }

    public Optional<String> findReservationStatus(UUID reservationId) {
        return jdbc.query("SELECT status FROM stock_reservations WHERE reservation_id = ?",
                (rs, rowNumber) -> rs.getString("status"), reservationId).stream().findFirst();
    }

    /** Flips RESERVED to RELEASED exactly once and returns what must be restocked. */
    public Optional<Reserved> markReleased(UUID reservationId) {
        return jdbc.query("""
                UPDATE stock_reservations SET status = 'RELEASED'
                WHERE reservation_id = ? AND status = 'RESERVED'
                RETURNING product_id, quantity
                """, (rs, rowNumber) -> new Reserved(rs.getLong("product_id"), rs.getInt("quantity")),
                reservationId).stream().findFirst();
    }

    public void restock(Long productId, Integer quantity) {
        jdbc.update("UPDATE inventory SET quantity = quantity + ? WHERE product_id = ?", quantity, productId);
    }

    public record Reserved(Long productId, Integer quantity) {
    }
}
